package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.service.HistoryGuess
import org.bson.Document
import org.springframework.stereotype.Component
import java.time.LocalDate

/** How far the import of the ranked history got, so it continues there after a restart, and what it couldn't be sure of */
@Component
class HistoryImportDataSource(
    database: MongoDatabase,
) {
    private val progress by lazy { database.getCollection("historyImport") }
    private val guesses by lazy { database.getCollection("historyGuesses") }

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
        progress.replaceOne(Filters.eq("_id", ID), document, ReplaceOptions().upsert(true))
    }

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
