package nl.greaper.bnplanner.datasource

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus.Companion.toPriorityStatus
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import org.bson.Document
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * How beatmaps are stored. Everything that has its default value is left out, like an empty note or a missing nominator.
 */
object StoredBeatmap {
    private const val NOMINATORS = 2

    /** The same beatmap, in a way that doesn't change when it is stored and read again */
    fun normalize(beatmap: Beatmap): Beatmap =
        beatmap.copy(
            gamemodes = beatmap.gamemodes.map { it.copy(nominators = fillNominators(it.nominators)) },
            dateAdded = beatmap.dateAdded.truncatedTo(ChronoUnit.MILLIS),
            dateUpdated = beatmap.dateUpdated.truncatedTo(ChronoUnit.MILLIS),
            dateRanked = beatmap.dateRanked?.truncatedTo(ChronoUnit.MILLIS),
        )

    fun toDocument(beatmap: Beatmap): Document {
        val document =
            Document("_id", toStoredId(beatmap.osuId))
                .append("artist", beatmap.artist)
                .append("title", beatmap.title)
                .append("mapper", toStoredId(beatmap.mapperId))
                .append("mapperName", beatmap.mapper)
                .append("status", beatmap.status.toPriorityStatus())
                .append("modes", beatmap.gamemodes.map { toDocument(it) })
                .append("added", Date.from(beatmap.dateAdded))
                .append("updated", Date.from(beatmap.dateUpdated))

        beatmap.note.takeIf { it.isNotEmpty() }?.let { document.append("note", it) }
        beatmap.dateRanked?.let { document.append("ranked", Date.from(it)) }
        beatmap.mainGamemodes?.let { main -> document.append("main", main.map { it.name }) }

        return document
    }

    private fun toDocument(gamemode: BeatmapGamemode): Document {
        val missing = BeatmapNominator(MISSING_USER_ID, false)
        return Document("mode", gamemode.gamemode.name)
            .append("by", gamemode.nominators.dropLastWhile { it == missing }.map { toDocument(it) })
    }

    private fun toDocument(nominator: BeatmapNominator): Document {
        val document = Document("user", toStoredId(nominator.nominatorId))

        if (nominator.hasNominated) {
            document.append("done", true)
        }

        return document
    }

    fun fromDocument(document: Document): Beatmap =
        Beatmap(
            osuId = document["_id"].toString(),
            artist = document.getString("artist"),
            title = document.getString("title"),
            note = document.get("note", ""),
            mapper = document.getString("mapperName"),
            mapperId = document["mapper"].toString(),
            status = findStatus(document),
            gamemodes = document.getList("modes", Document::class.java).map { toGamemode(it) },
            dateAdded = document.getDate("added").toInstant(),
            dateUpdated = document.getDate("updated").toInstant(),
            dateRanked = findInstant(document, "ranked"),
            mainGamemodes = findGamemodes(document, "main"),
        )

    private fun toGamemode(document: Document): BeatmapGamemode =
        BeatmapGamemode(
            gamemode = Gamemode.valueOf(document.getString("mode")),
            nominators =
                fillNominators(
                    document
                        .getList("by", Document::class.java)
                        .map { BeatmapNominator(it["user"].toString(), it.getBoolean("done", false)) },
                ),
        )

    /** Read how beatmaps were stored before */
    fun fromOldDocument(document: Document): Beatmap =
        normalize(
            Beatmap(
                osuId = document["_id"].toString(),
                artist = document.getString("artist"),
                title = document.getString("title"),
                note = document.get("note", ""),
                mapper = document.getString("mapper"),
                mapperId = document["mapperId"].toString(),
                status = findStatus(document),
                gamemodes = document.getList("gamemodes", Document::class.java).map { toOldGamemode(it) },
                dateAdded = document.getDate("dateAdded").toInstant(),
                dateUpdated = document.getDate("dateUpdated").toInstant(),
                dateRanked = findInstant(document, "dateRanked"),
                mainGamemodes = findGamemodes(document, "mainGamemodes"),
            ),
        )

    private fun toOldGamemode(document: Document): BeatmapGamemode =
        BeatmapGamemode(
            gamemode = Gamemode.valueOf(document.getString("gamemode")),
            nominators =
                document
                    .getList("nominators", Document::class.java)
                    .map { BeatmapNominator(it["nominatorId"].toString(), it.getBoolean("hasNominated", false)) },
        )

    fun toOldDocument(beatmap: Beatmap): Document =
        Document("_id", beatmap.osuId)
            .append("artist", beatmap.artist)
            .append("title", beatmap.title)
            .append("note", beatmap.note)
            .append("mapper", beatmap.mapper)
            .append("mapperId", beatmap.mapperId)
            .append("status", beatmap.status.toPriorityStatus())
            .append("gamemodes", beatmap.gamemodes.map { toOldDocument(it) })
            .append("dateAdded", Date.from(beatmap.dateAdded))
            .append("dateUpdated", Date.from(beatmap.dateUpdated))
            .append("dateRanked", beatmap.dateRanked?.let { Date.from(it) })
            .append("mainGamemodes", beatmap.mainGamemodes?.map { it.name })

    private fun toOldDocument(gamemode: BeatmapGamemode): Document =
        Document("gamemode", gamemode.gamemode.name)
            .append(
                "nominators",
                gamemode.nominators.map { Document("nominatorId", it.nominatorId).append("hasNominated", it.hasNominated) },
            )
            // Not used anymore, but the previous version needs it
            .append("isReady", false)

    private fun findStatus(document: Document): BeatmapStatus =
        requireNotNull(BeatmapStatus.fromPriorityStatus((document["status"] as Number).toInt())) { "Unknown status" }

    private fun findInstant(
        document: Document,
        name: String,
    ): Instant? = document.getDate(name)?.toInstant()

    private fun findGamemodes(
        document: Document,
        name: String,
    ): List<Gamemode>? = document.getList(name, String::class.java)?.map { Gamemode.valueOf(it) }

    private fun fillNominators(nominators: List<BeatmapNominator>): List<BeatmapNominator> =
        nominators + List(maxOf(0, NOMINATORS - nominators.size)) { BeatmapNominator(MISSING_USER_ID, false) }

    /** Numbers are a lot smaller, but only used when they read as the exact same id */
    fun toStoredId(id: String): Any = id.toIntOrNull()?.takeIf { it.toString() == id } ?: id
}
