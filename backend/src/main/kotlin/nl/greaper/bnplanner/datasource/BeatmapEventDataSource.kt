package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import org.bson.Document
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date

/** The history of the beatmaps */
@Component
class BeatmapEventDataSource(
    database: MongoDatabase,
) : BaseDataSource<BeatmapEvent>(database, COLLECTION) {
    companion object {
        const val COLLECTION = "beatmapEvents"
    }

    init {
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("beatmapId"), Indexes.descending("at")))
        // For the weekly summary, which asks what reached a status lately among tens of thousands of events
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("at")))
    }

    override fun toDocument(value: BeatmapEvent): Document =
        Document("beatmapId", value.beatmapId)
            .append("type", value.type.name)
            .append("at", Date.from(value.at))
            .append("userId", value.userId)
            .append("gamemodes", value.gamemodes.map { it.name })
            .append("nominatorId", value.nominatorId)
            .append("oldNominatorId", value.oldNominatorId)
            .append("status", value.status?.name)
            .append("osuEvent", value.osuEvent?.name)

    override fun fromDocument(document: Document): BeatmapEvent =
        BeatmapEvent(
            beatmapId = document.getString("beatmapId"),
            type = BeatmapEventType.valueOf(document.getString("type")),
            at = document.getDate("at").toInstant(),
            userId = document.getString("userId"),
            gamemodes = document.getList("gamemodes", String::class.java).map { Gamemode.valueOf(it) },
            nominatorId = document.getString("nominatorId"),
            oldNominatorId = document.getString("oldNominatorId"),
            status = document.getString("status")?.let { BeatmapStatus.valueOf(it) },
            osuEvent = document.getString("osuEvent")?.let { BeatmapsetEventType.valueOf(it) },
        )

    fun insert(event: BeatmapEvent) {
        insertOne(event)
    }

    fun insertAll(events: Collection<BeatmapEvent>) {
        events.chunked(BeatmapDataSource.BATCH_SIZE).forEach { batch -> collection.insertMany(batch.map { toDocument(it) }) }
    }

    /** Everything that happened to these beatmaps, oldest first */
    fun findAllOf(beatmapIds: Collection<String>): List<BeatmapEvent> =
        beatmapIds.chunked(BeatmapDataSource.BATCH_SIZE).flatMap { ids ->
            collection
                .find(Filters.`in`("beatmapId", ids))
                .sort(Sorts.ascending("at", "_id"))
                .map { fromDocument(it) }
                .toList()
        }

    /** Newest first, what happened at the same moment the last recorded first */
    fun findNewest(
        beatmapId: String,
        limit: Int,
    ): List<BeatmapEvent> =
        collection
            .find(Filters.eq("beatmapId", beatmapId))
            .sort(Sorts.descending("at", "_id"))
            .limit(limit)
            .map { fromDocument(it) }
            .toList()

    /** The ids of the beatmaps that got this status since then, by any kind of event */
    fun findBeatmapsReaching(
        status: BeatmapStatus,
        since: Instant,
    ): Set<String> =
        collection
            .find(Filters.and(Filters.eq("status", status.name), Filters.gte("at", Date.from(since))))
            .map { it.getString("beatmapId") }
            .toSet()
}
