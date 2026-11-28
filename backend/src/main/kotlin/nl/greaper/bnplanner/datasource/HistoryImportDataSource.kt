package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.service.HistoryGuess
import org.bson.Document
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.Date

/** How far the import of the ranked history got, so it continues there after a restart, and what it couldn't be sure of */
@Component
class HistoryImportDataSource(
    database: MongoDatabase,
) {
    private val progress by lazy { database.getCollection("historyImport") }
    private val guesses by lazy { database.getCollection("historyGuesses") }
    private val nominations by lazy {
        database.getCollection("historyNominations").also { it.createIndex(Indexes.ascending("beatmapsetId")) }
    }

    /** A nomination, reset or disqualification of a beatmap set, as osu! keeps them since 2008 */
    data class NominationEvent(
        val id: Long,
        val beatmapsetId: String,
        val kind: Kind,
        val at: Instant,
        val userId: String? = null,
        /** The gamemodes of a nomination, when osu! kept them */
        val modes: List<String>? = null,
    ) {
        enum class Kind {
            Nominate,

            /** Takes away every nomination before it, like a disqualification */
            Reset,
        }
    }

    data class Progress(
        /** The first day the current search of osu! starts at, null for the oldest ranked beatmap */
        val since: LocalDate? = null,
        /** Where the current search of osu! continues, null at its start */
        val cursor: String? = null,
        /** Pages read of the current search, osu! stops after 10,000 results */
        val pages: Int = 0,
        /** Every ranked beatmap of osu! was seen */
        val done: Boolean = false,
        val seen: Int = 0,
        val imported: Int = 0,
        /** The first day of which osu! wasn't asked for the nominations yet, null before it starts */
        val nominationsFrom: LocalDate? = null,
        /** Every nomination of osu! until the day the beatmaps are looked at was read */
        val nominationsDone: Boolean = false,
        /** Beatmap sets osu! was asked about on their own, as their nominations didn't tell enough */
        val askedAbout: Int = 0,
        /** Beatmap sets of which the nominations were compared with what osu! tells of the beatmap set itself */
        val checked: Int = 0,
        /** Those where the two didn't agree, which shows whether the nominations can be trusted */
        val disagreed: Int = 0,
    )

    fun findProgress(): Progress {
        val document = progress.find(Filters.eq("_id", ID)).first() ?: return Progress()

        return Progress(
            since = document.getString("since")?.let { LocalDate.parse(it) },
            cursor = document.getString("cursor"),
            pages = document.getInteger("pages", 0),
            done = document.getBoolean("done", false),
            seen = document.getInteger("seen", 0),
            imported = document.getInteger("imported", 0),
            nominationsFrom = document.getString("nominationsFrom")?.let { LocalDate.parse(it) },
            nominationsDone = document.getBoolean("nominationsDone", false),
            askedAbout = document.getInteger("askedAbout", 0),
            checked = document.getInteger("checked", 0),
            disagreed = document.getInteger("disagreed", 0),
        )
    }

    fun saveProgress(value: Progress) {
        val document =
            Document("_id", ID)
                .append("since", value.since?.toString())
                .append("cursor", value.cursor)
                .append("pages", value.pages)
                .append("done", value.done)
                .append("seen", value.seen)
                .append("imported", value.imported)
                .append("nominationsFrom", value.nominationsFrom?.toString())
                .append("nominationsDone", value.nominationsDone)
                .append("askedAbout", value.askedAbout)
                .append("checked", value.checked)
                .append("disagreed", value.disagreed)
        progress.replaceOne(Filters.eq("_id", ID), document, ReplaceOptions().upsert(true))
    }

    fun saveNominations(events: List<NominationEvent>) {
        events.chunked(500).forEach { batch ->
            nominations.bulkWrite(
                batch.map { event ->
                    val document =
                        Document("_id", event.id)
                            .append("beatmapsetId", event.beatmapsetId)
                            .append("kind", event.kind.name)
                            .append("at", Date.from(event.at))
                            .append("userId", event.userId)
                            .append("modes", event.modes)
                    ReplaceOneModel(Filters.eq("_id", event.id), document, ReplaceOptions().upsert(true))
                },
            )
        }
    }

    fun findNominations(beatmapsetId: String): List<NominationEvent> =
        nominations
            .find(Filters.eq("beatmapsetId", beatmapsetId))
            .map { document ->
                NominationEvent(
                    id = (document.get("_id") as Number).toLong(),
                    beatmapsetId = document.getString("beatmapsetId"),
                    kind = NominationEvent.Kind.valueOf(document.getString("kind")),
                    at = document.getDate("at").toInstant(),
                    userId = document.getString("userId"),
                    modes = document.getList("modes", String::class.java),
                )
            }.toList()

    fun saveGuess(guess: HistoryGuess) {
        val document =
            Document("_id", guess.beatmapId)
                .append("modes", guess.modes.map { it.name })
                .append("main", guess.main.name)
                .append("certain", Document(guess.certain.mapValues { (_, modes) -> modes.map { it.name } }))
                .append("unknown", guess.unknown)
                .append("year", guess.year)
        guesses.replaceOne(Filters.eq("_id", guess.beatmapId), document, ReplaceOptions().upsert(true))
    }

    fun findGuesses(): List<HistoryGuess> =
        guesses
            .find()
            .map { document ->
                val certain = document.get("certain", Document::class.java) ?: Document()
                HistoryGuess(
                    beatmapId = document.getString("_id"),
                    modes = document.getList("modes", String::class.java).map { Gamemode.valueOf(it) },
                    main = Gamemode.valueOf(document.getString("main")),
                    certain = certain.keys.associateWith { key -> certain.getList(key, String::class.java).map { Gamemode.valueOf(it) } },
                    unknown = document.getList("unknown", String::class.java),
                    year = document.getInteger("year"),
                )
            }.toList()

    companion object {
        const val ID = "ranked"
    }
}
