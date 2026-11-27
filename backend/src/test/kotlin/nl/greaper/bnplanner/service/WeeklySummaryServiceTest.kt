package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.support.RecordingDiscord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class WeeklySummaryServiceTest {
    private val planner = Planner()

    init {
        planner.user("20", "Rory", nominator(Gamemode.osu), nominator(Gamemode.taiko))
    }

    private fun service() = WeeklySummaryService(planner.beatmaps, planner.events, planner.userService, planner.discord)

    private fun daysAgo(days: Long): Instant = NOW.minus(Duration.ofDays(days))

    private fun link(id: String) = "[Artist $id - Title $id](https://osu.ppy.sh/beatmapsets/$id)"

    private val nothing =
        """
        **Gone quiet (no change for 21 days or more)**
        Nothing has gone quiet.

        **Longest waiting for a second nominator**
        Nobody is waiting for a second nominator.

        **Last 7 days:** 0 qualified, 0 ranked
        """.trimIndent()

    @Test
    fun `Should tell what went quiet, oldest first, leaving out what is qualified or done`() {
        planner.save(beatmap("1", updated = daysAgo(21)))
        planner.save(beatmap("2", updated = daysAgo(40)))
        planner.save(beatmap("3", updated = daysAgo(20)))
        planner.save(beatmap("4", updated = daysAgo(50), status = BeatmapStatus.Qualified))
        planner.save(beatmap("5", updated = daysAgo(50), status = BeatmapStatus.Graved))
        planner.save(beatmap("6", mode(Gamemode.mania), updated = daysAgo(50)))

        val osu = service().summarize(NOW).getValue(Gamemode.osu)

        assertEquals(
            """
            **Gone quiet (no change for 21 days or more)**
            - ${link("2")}, 40 days
            - ${link("1")}, 21 days
            """.trimIndent(),
            osu.substringBefore("\n\n"),
        )
    }

    @Test
    fun `Should only show the oldest that went quiet`() {
        (1..12).forEach { planner.save(beatmap("$it", updated = daysAgo(30L + it))) }

        val quiet =
            service()
                .summarize(NOW)
                .getValue(Gamemode.osu)
                .substringBefore("\n\n")
                .lines()

        assertEquals(12, quiet.size)
        assertEquals("- ${link("12")}, 42 days", quiet[1])
        assertEquals("and 2 more", quiet.last())
    }

    @Test
    fun `Should tell the five longest waiting for a second nominator in a mode that needs two`() {
        (1..6).forEach { planner.save(beatmap("$it", mode(Gamemode.osu, "20"), updated = daysAgo(it.toLong()))) }
        // Both known, or only one needed
        planner.save(beatmap("7", mode(Gamemode.osu, "20", "21"), updated = daysAgo(10)))
        planner.save(beatmap("8", mode(Gamemode.osu, "21"), mode(Gamemode.taiko, "20"), updated = daysAgo(10), main = listOf(Gamemode.osu)))
        // The main mode is undecided, or the only one, and the nominator unknown
        planner.save(beatmap("9", mode(Gamemode.osu, "0", "77"), mode(Gamemode.taiko, "20", "21"), updated = daysAgo(1)))

        val waiting =
            service()
                .summarize(NOW)
                .getValue(Gamemode.osu)
                .split("\n\n")[1]

        assertEquals(
            """
            **Longest waiting for a second nominator**
            - ${link("8")}, with Unknown for 10 days
            - ${link("6")}, with Rory for 6 days
            - ${link("5")}, with Rory for 5 days
            - ${link("4")}, with Rory for 4 days
            - ${link("3")}, with Rory for 3 days
            and 3 more
            """.trimIndent(),
            waiting,
        )
        // Only one is needed there
        assertEquals(
            "Nobody is waiting for a second nominator.",
            service()
                .summarize(NOW)
                .getValue(Gamemode.taiko)
                .split("\n\n")[1]
                .lines()[1],
        )
    }

    @Test
    fun `Should count what qualified and ranked last week in a mode`() {
        planner.save(beatmap("1", status = BeatmapStatus.Qualified))
        planner.save(beatmap("2", status = BeatmapStatus.Ranked))
        planner.save(beatmap("3", mode(Gamemode.taiko), status = BeatmapStatus.Ranked))
        planner.save(beatmap("4", status = BeatmapStatus.Ranked))
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.StatusChanged, daysAgo(1), status = BeatmapStatus.Qualified))
        planner.events.insert(BeatmapEvent("2", BeatmapEventType.StatusChanged, daysAgo(6), status = BeatmapStatus.Qualified))
        planner.events.insert(BeatmapEvent("2", BeatmapEventType.Synced, daysAgo(2), status = BeatmapStatus.Ranked))
        planner.events.insert(BeatmapEvent("3", BeatmapEventType.StatusChanged, daysAgo(2), status = BeatmapStatus.Ranked))
        planner.events.insert(BeatmapEvent("4", BeatmapEventType.StatusChanged, daysAgo(8), status = BeatmapStatus.Ranked))

        val summary = service().summarize(NOW)

        assertEquals("**Last 7 days:** 2 qualified, 1 ranked", summary.getValue(Gamemode.osu).lines().last())
        assertEquals("**Last 7 days:** 0 qualified, 1 ranked", summary.getValue(Gamemode.taiko).lines().last())
    }

    @Test
    fun `Should preview every mode`() {
        planner.save(beatmap("1", mode(Gamemode.mania, "20"), updated = daysAgo(1)))

        val preview = service().preview(NOW)

        assertEquals("# osu\n$nothing", preview.substringBefore("\n\n# taiko"))
        assertEquals(listOf("# osu", "# taiko", "# catch", "# mania"), preview.lines().filter { it.startsWith("# ") })
        assertEquals("- ${link("1")}, with Rory for 1 day", preview.substringAfter("# mania").lines()[5])
        assertEquals(Gamemode.entries.size, service().preview().split("# ").size - 1)
    }

    @Test
    fun `Should post every mode to its own channels`() {
        service().post(NOW)

        assertEquals(
            Gamemode.entries.map {
                RecordingDiscord.Sent(
                    nothing,
                    EmbedColor.BLUE,
                    false,
                    listOf(it),
                    EmbedThumbnail(""),
                    EmbedFooter("Weekly summary of ${if (it == Gamemode.fruits) "catch" else it.name}"),
                )
            },
            planner.discord.sent,
        )
        service().post()
        assertEquals(8, planner.discord.sent.size)
    }

    @Test
    fun `Should post nothing without Discord`() {
        val withoutDiscord = Planner(withDiscord = false)

        WeeklySummaryService(withoutDiscord.beatmaps, withoutDiscord.events, withoutDiscord.userService, null).post(NOW)

        assertEquals(emptyList<RecordingDiscord.Sent>(), withoutDiscord.discord.sent)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-11-30T09:00:00Z")
    }
}
