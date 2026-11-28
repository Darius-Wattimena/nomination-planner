package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class HistoryImportServiceTest {
    private val planner = Planner()
    private val service = planner.historyImport

    private val search = "GET /api/v2/beatmapsets/search?s=ranked&sort=ranked_asc&nsfw=true"

    @BeforeEach
    fun osu() {
        FakeOsu.token("own")
        MeGroup.SupportedGroups.forEach { FakeOsu.groupHistory(it, """{"events":[]}""") }
        listOf("20", "21", "30").forEach { FakeOsu.user(it, "BN $it") }
    }

    private fun set(
        id: String,
        ranked: Int = 1,
        modes: List<String> = listOf("osu"),
        nominations: List<Pair<String, String?>> = emptyList(),
        main: String = "osu",
    ): String {
        val difficulties = modes.joinToString { """{"total_length":100,"user_id":10,"mode":"$it","version":"$it"}""" }
        val current =
            nominations.joinToString { (user, rulesets) -> """{"rulesets":${rulesets ?: "null"},"reset":false,"user_id":$user}""" }
        return """
            {"id":$id,"artist":"Artist $id","title":"Title $id","creator":"Mapper","user_id":10,"ranked":$ranked,
            "ranked_date":"2014-0${id.last()}-01T00:00:00Z","last_updated":"2014-01-01T00:00:00Z","beatmaps":[$difficulties],
            "current_nominations":[$current],"nominations_summary":{"eligible_main_rulesets":["$main"]}}
            """.trimIndent()
    }

    private fun page(
        vararg sets: String,
        cursor: String? = null,
    ) = """{"beatmapsets":[${sets.joinToString()}],"cursor_string":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    private fun details(vararg sets: Pair<String, String>) =
        sets.forEach { (id, body) -> FakeOsu.respond("GET /api/v2/beatmapsets/$id", body) }

    private fun setUp() {
        planner.save(beatmap("3", status = BeatmapStatus.Ranked, title = "Planned"))
        val single = set("1", nominations = listOf("20" to null, "21" to null))
        val taiko = set("4", modes = listOf("taiko"), main = "taiko", nominations = listOf("30" to null, "2778" to null))
        val hybrid = set("5", modes = listOf("osu", "osu", "taiko"), nominations = listOf("30" to null, "20" to null))
        FakeOsu.respond(search, page(set("1"), set("2", ranked = 4), set("3"), set("4"), set("5"), cursor = "next+1="))
        FakeOsu.respond("$search&cursor_string=next%2B1%3D", page(set("6")))
        details("1" to single, "2" to set("2", ranked = 4), "4" to taiko, "5" to hybrid, "6" to set("6"))
    }

    private fun nominated(
        gamemode: Gamemode,
        vararg ids: String,
        guessed: Boolean = false,
    ) = BeatmapGamemode(
        gamemode,
        ids.map {
            BeatmapNominator(it, true)
        } + List(maxOf(0, 2 - ids.size)) { BeatmapNominator("0", false) },
        guessed,
    )

    @Test
    fun `Should import every ranked beatmap with its nominators and work out the modes`() {
        setUp()

        assertEquals(true, service.start())
        service.finish()

        val status = service.findStatus()
        assertNull(status.failed)
        assertEquals(6, status.seen)
        assertEquals(3, status.imported)
        assertEquals(true, status.done)

        val single = planner.stored("1")
        assertEquals(BeatmapStatus.Ranked, single.status)
        assertEquals(listOf(nominated(Gamemode.osu, "20", "21")), single.gamemodes)
        assertEquals(Instant.parse("2014-01-01T00:00:00Z"), single.dateRanked)
        assertEquals(single.dateRanked, single.dateAdded)
        // Loved, already there, or without nominators
        assertNull(planner.beatmaps.findById("2"))
        assertEquals("Planned", planner.stored("3").title)
        assertNull(planner.beatmaps.findById("6"))
        assertEquals(false, FakeOsu.calls().any { it.endsWith("/beatmapsets/3") })

        // Who nominated which mode of the hybrid comes from their other nominations
        assertEquals(
            listOf(nominated(Gamemode.osu, "20", guessed = true), nominated(Gamemode.taiko, "30", guessed = true)),
            planner.stored("5").gamemodes,
        )
        // A restricted nominator gets the name of the staff log
        assertEquals("Zyid", planner.users.findUser("2778")!!.username)
        assertEquals(true, planner.users.findUser("2778")!!.restricted)
        assertEquals("Mapper", planner.users.findUser("10")!!.username)
        // The staff log is part of the role history
        assertEquals(true, planner.roleHistory.findAll().any { it.userId == "2778" })
        // Slower than the rest of the planner
        assertEquals(true, planner.historySleeps.isNotEmpty() && planner.historySleeps.all { it == 1_000L })
        assertEquals(emptyList<Any>(), planner.discord.sent)
    }

    @Test
    fun `Should continue where it stopped`() {
        setUp()
        FakeOsu.respond("$search&cursor_string=next%2B1%3D", "{}", 503)

        service.start()
        service.finish()
        assertEquals(true, service.findStatus().failed!!.startsWith("Stopped"))
        assertEquals(5, planner.historyProgress.findProgress().seen)

        FakeOsu.respond("$search&cursor_string=next%2B1%3D", page(set("6")))
        service.start()
        service.finish()

        assertEquals(true, service.findStatus().done)
        assertEquals(1, FakeOsu.calls().count { it == search })
        assertEquals(3, planner.beatmaps.list().count { it.status == BeatmapStatus.Ranked && it.osuId != "3" })
    }

    @Test
    fun `Should start a new search at the last day reached, before osu stops at 10,000 results`() {
        setUp()
        service.pagesPerSearch = 1
        // The last beatmap of the first page was ranked on 2014-05-01, which is seen again and skipped
        FakeOsu.respond("$search&q=ranked%3E%3D2014-05-01", page(set("5"), set("6")))

        service.start()
        service.finish()

        assertNull(service.findStatus().failed)
        assertEquals(true, service.findStatus().done)
        assertEquals(false, FakeOsu.calls().any { it.contains("cursor_string") })
        assertEquals(1, FakeOsu.calls().count { it == "GET /api/v2/beatmapsets/5" })
        assertEquals(3, service.findStatus().imported)
    }

    @Test
    fun `Should leave modes corrected by hand when working them out again`() {
        setUp()
        service.start()
        service.finish()
        planner.beatmaps.modify("5") { it.copy(gamemodes = listOf(mode(Gamemode.taiko, "20", "30", true, true))) }

        service.startModes()
        service.finish()

        assertEquals(listOf(mode(Gamemode.taiko, "20", "30", true, true)), planner.stored("5").gamemodes)
        assertEquals(1, service.findStatus().guessed)
    }
}
