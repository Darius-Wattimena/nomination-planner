package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.RankedBeatmap
import nl.greaper.bnplanner.model.RankedMode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.ADDED
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RankedServiceTest {
    private val planner = Planner()
    private val service = RankedService(planner.beatmaps, planner.users, FeedTokens(planner.beatmaps, planner.users))

    @Test
    fun `Should find every ranked beatmap`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.user("10", "Mapper")
        BeatmapStatus.entries.forEach { planner.save(beatmap(it.name, status = it)) }
        planner.save(
            beatmap(
                "1",
                mode(Gamemode.osu, "5", "6"),
                mode(Gamemode.mania, "0", "5"),
                status = BeatmapStatus.Ranked,
                ranked = UPDATED,
            ),
        )

        val update = service.find(null)
        assertTrue(update.isComplete)
        assertEquals(setOf("Ranked", "1"), update.beatmaps.map { it.id }.toSet())
        assertEquals(
            RankedBeatmap(
                "1",
                "Title 1",
                "10",
                UPDATED.epochSecond,
                listOf(RankedMode(Gamemode.osu, listOf("5", "6")), RankedMode(Gamemode.mania, listOf("5"))),
                ADDED.epochSecond,
            ),
            update.beatmaps.single { it.id == "1" },
        )
        // Unknown people have no name
        assertEquals(mapOf("5" to "BN", "10" to "Mapper"), update.names)
    }

    @Test
    fun `Should find what changed`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.save(beatmap("1", status = BeatmapStatus.Ranked))
        planner.save(beatmap("2", status = BeatmapStatus.Ranked))
        val first = service.find(null)
        val nothing = service.find(first.token)
        assertEquals(emptyList<Any>(), nothing.beatmaps + nothing.removed + nothing.names.keys)

        planner.beatmaps.update(beatmap("1", mode(Gamemode.osu, "5"), status = BeatmapStatus.Ranked))
        planner.beatmaps.update(beatmap("2", status = BeatmapStatus.Pending))
        planner.beatmaps.update(beatmap("3"))
        planner.beatmaps.update(beatmap("4", status = BeatmapStatus.Ranked))
        planner.beatmaps.deleteById("4")
        planner.user("6", "New")
        val update = service.find(first.token)
        assertFalse(update.isComplete)
        assertEquals(listOf("1"), update.beatmaps.map { it.id })
        assertEquals(setOf("2", "3", "4"), update.removed.toSet())
        assertEquals(mapOf("5" to "BN", "6" to "New"), update.names)
        assertTrue(service.find("unknown").isComplete)
    }

    @Test
    fun `Should only send the names after a restart when no ranked beatmap changed`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.save(beatmap("1", mode(Gamemode.osu, "5"), status = BeatmapStatus.Ranked))
        val before = service.find(null)

        // Another run of the backend, of which the versions start over
        val restarted = RankedService(planner.beatmaps, planner.users, FeedTokens(planner.beatmaps, planner.users))
        planner.save(beatmap("2", status = BeatmapStatus.Pending))
        val update = restarted.find(before.token)
        assertFalse(update.isComplete)
        assertEquals(emptyList<Any>(), update.beatmaps + update.removed)
        assertEquals("BN", update.names["5"])
        assertEquals(true, restarted.find(update.token).beatmaps.isEmpty())

        val again = RankedService(planner.beatmaps, planner.users, FeedTokens(planner.beatmaps, planner.users))
        planner.save(beatmap("3", status = BeatmapStatus.Ranked))
        val changed = again.find(update.token)
        assertTrue(changed.isComplete)
        assertEquals(setOf("1", "3"), changed.beatmaps.map { it.id }.toSet())
    }

    @Test
    fun `Should put everything together once until a ranked beatmap changes`() {
        var now = 0L
        val kept =
            RankedService(
                planner.beatmaps,
                planner.users,
                FeedTokens(planner.beatmaps, planner.users),
                rebuildEveryMillis = 1_000,
                clock = { now },
            )
        planner.save(beatmap("1", status = BeatmapStatus.Ranked))
        val first = kept.findJson(null)

        planner.save(beatmap("3", status = BeatmapStatus.Ranked))
        // Not again within a second, what changed comes with the next update
        now = 500
        assertTrue(first === kept.findJson(null))
        now = 2_000
        val second = kept.findJson(null)
        assertEquals(
            setOf("1", "3"),
            kept
                .find(null)
                .beatmaps
                .map { it.id }
                .toSet(),
        )

        // Only ranked beatmaps matter
        planner.save(beatmap("2", status = BeatmapStatus.Pending))
        now = 10_000
        assertTrue(second === kept.findJson(null))
    }
}
