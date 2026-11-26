package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus.Companion.toPriorityStatus
import org.bson.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * All beatmaps are kept in memory, MongoDB is only used to store them. So there can only be a single running backend.
 *
 * As long as the old collection is kept, it is the one that counts. The previous version of the backend can be started at any
 * time, and whatever it changed is copied again by the next migration.
 */
@Component
class BeatmapDataSource(
    private val database: MongoDatabase,
    @Value("\${beatmaps.keep-old}") private val keepOld: Boolean,
) {
    companion object {
        const val COLLECTION = "beatmaps2"
        const val OLD_COLLECTION = "beatmaps"
        const val MIGRATIONS = "migrations"
        const val BATCH_SIZE = 500

        private val ORDER =
            compareBy<Beatmap> { it.status.toPriorityStatus() }
                .thenByDescending { it.dateUpdated }
                .thenBy { it.osuId }
    }

    data class MigrationResult(
        val written: Int,
        val removed: Int,
        val different: Int,
        val unreadable: Int,
    )

    private val log = KotlinLogging.logger { }
    private val beatmaps = ConcurrentHashMap<String, Beatmap>()
    private var unreadable = emptySet<String>()
    final val changes = ChangeLog()
    private var usesOld = true

    private fun collection(): MongoCollection<Document> = database.getCollection(COLLECTION)

    private fun oldCollection(): MongoCollection<Document> = database.getCollection(OLD_COLLECTION)

    init {
        load()
    }

    /** Read everything from the database again */
    @Synchronized
    fun load() {
        val migration = database.getCollection(MIGRATIONS).find(Filters.eq("_id", COLLECTION)).first()
        val oldIsOutdated = migration?.getBoolean("oldIsOutdated", false) == true
        usesOld = !oldIsOutdated && (keepOld || migration == null)

        if (!usesOld && !oldIsOutdated) {
            // From now on it misses changes, so it may never be used again
            database.getCollection(MIGRATIONS).updateOne(Filters.eq("_id", COLLECTION), Updates.set("oldIsOutdated", true))
        }

        beatmaps.clear()
        unreadable = emptySet()

        val documents = if (usesOld) oldCollection().find() else collection().find()

        documents.forEach { document ->
            try {
                val beatmap = if (usesOld) StoredBeatmap.fromOldDocument(document) else StoredBeatmap.fromDocument(document)
                beatmaps[beatmap.osuId] = beatmap
            } catch (ex: Exception) {
                unreadable = unreadable + document["_id"].toString()
                log.error(ex) { "Unable to read beatmap ${document["_id"]}" }
            }
        }

        log.info { "Loaded ${beatmaps.size} beatmaps (old collection = $usesOld)" }
    }

    fun list(): List<Beatmap> = beatmaps.values.toList()

    fun findById(id: String): Beatmap? = beatmaps[id]

    fun count(filter: (Beatmap) -> Boolean): Int = beatmaps.values.count(filter)

    fun findAll(filter: (Beatmap) -> Boolean): List<Beatmap> = beatmaps.values.filter(filter).sortedWith(ORDER)

    fun findAll(
        filter: (Beatmap) -> Boolean,
        from: Int,
        to: Int,
    ): List<Beatmap> = findAll(filter).drop(maxOf(0, from)).take(maxOf(0, to - from))

    fun findAll(
        filter: (Beatmap) -> Boolean,
        pageNumber: Int,
        pageLimit: PageLimit,
    ): List<Beatmap> {
        val limit =
            when (pageLimit) {
                PageLimit.TEN -> 10
                PageLimit.TWENTY -> 20
                PageLimit.FIFTY -> 50
            }
        val from = limit * maxOf(0, pageNumber - 1)

        return findAll(filter, from, from + limit)
    }

    @Synchronized
    fun insertOne(beatmap: Beatmap) {
        check(!beatmaps.containsKey(beatmap.osuId)) { "Beatmap ${beatmap.osuId} already exists" }
        update(beatmap)
    }

    fun insertMany(beatmaps: List<Beatmap>) = beatmaps.forEach { insertOne(it) }

    /** Null when it was added, otherwise the beatmap someone else added first */
    @Synchronized
    fun insertIfMissing(beatmap: Beatmap): Beatmap? {
        beatmaps[beatmap.osuId]?.let { return it }

        update(beatmap)
        return null
    }

    /** Only visible to others once it is stored */
    @Synchronized
    fun update(beatmap: Beatmap) {
        val normalized = StoredBeatmap.normalize(beatmap)

        if (usesOld) {
            val document = StoredBeatmap.toOldDocument(normalized)
            oldCollection().replaceOne(Filters.eq("_id", document["_id"]), document, ReplaceOptions().upsert(true))
        }

        val document = StoredBeatmap.toDocument(normalized)
        collection().replaceOne(Filters.eq("_id", document["_id"]), document, ReplaceOptions().upsert(true))
        beatmaps[normalized.osuId] = normalized
        changes.mark(normalized.osuId)
    }

    /**
     * Change the current version of a beatmap, nobody else can change it in between. So this has to be quick.
     *
     * @param change returns null when nothing has to be changed
     * @return the changed beatmap, or null when nothing changed
     */
    @Synchronized
    fun modify(
        id: String,
        change: (current: Beatmap) -> Beatmap?,
    ): Beatmap? {
        val changed = beatmaps[id]?.let(change) ?: return null
        update(changed)

        return beatmaps[id]
    }

    @Synchronized
    fun deleteById(id: String): Boolean {
        if (usesOld) {
            oldCollection().deleteOne(Filters.eq("_id", id))
        }

        collection().deleteOne(Filters.eq("_id", StoredBeatmap.toStoredId(id)))

        val isDeleted = beatmaps.remove(id) != null
        changes.mark(id)

        return isDeleted
    }

    /**
     * Copy the old collection, which stays as it is. Can be stopped at any time, as it only writes what is missing or different.
     *
     * @return null when there is nothing to migrate from
     */
    fun migrate(): MigrationResult? {
        if (!usesOld) {
            return null
        }

        val written = beatmaps.keys.chunked(BATCH_SIZE).sumOf { copy(it) }
        val removed = removeUnknown()
        val different = countDifferent()
        val result = MigrationResult(written, removed, different, unreadable.size)

        if (different == 0 && unreadable.isEmpty()) {
            database.getCollection(MIGRATIONS).replaceOne(
                Filters.eq("_id", COLLECTION),
                Document("_id", COLLECTION).append("date", Date()),
                ReplaceOptions().upsert(true),
            )
            log.info { "Migrated the beatmaps: $result" }
        } else {
            log.error { "The beatmaps are not fully migrated: $result, unreadable = $unreadable" }
        }

        return result
    }

    @Synchronized
    private fun copy(ids: List<String>): Int {
        val wanted = ids.mapNotNull { beatmaps[it] }.map { StoredBeatmap.toDocument(it) }
        val stored = collection().find(Filters.`in`("_id", wanted.map { it["_id"] })).associateBy { it["_id"] }
        val writes =
            wanted
                .filter { stored[it["_id"]] != it }
                .map { ReplaceOneModel(Filters.eq("_id", it["_id"]), it, ReplaceOptions().upsert(true)) }

        if (writes.isNotEmpty()) {
            collection().bulkWrite(writes)
        }

        return writes.size
    }

    @Synchronized
    private fun removeUnknown(): Int =
        collection()
            .deleteMany(Filters.nin("_id", beatmaps.keys.map { StoredBeatmap.toStoredId(it) }))
            .deletedCount
            .toInt()

    @Synchronized
    private fun countDifferent(): Int {
        val stored = collection().find().associate { it["_id"].toString() to StoredBeatmap.fromDocument(it) }

        return (stored.keys + beatmaps.keys).count { stored[it] != beatmaps[it] }
    }
}
