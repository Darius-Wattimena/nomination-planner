package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PlannerBeatmap
import nl.greaper.bnplanner.model.PlannerMode
import nl.greaper.bnplanner.model.PlannerNominator
import nl.greaper.bnplanner.model.PlannerUpdate
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.ADDED
import nl.greaper.bnplanner.support.Planner.Companion.LOGIN
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class PlannerServiceTest {
    private val planner = Planner()
    private val service = PlannerService(planner.beatmaps, planner.users, FeedTokens(planner.beatmaps, planner.users))

    private fun PlannerUpdate.userIds() = users.map { it.osuId }.toSet()

    private fun PlannerUpdate.beatmapIds() = beatmaps.map { it.id }.toSet()

    @Test
    fun `Should find everything in progress`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.user("10", "Mapper")
        planner.save(
            beatmap(
                "1",
                mode(Gamemode.osu, "5", "0", firstNominated = true),
                mode(Gamemode.taiko, "0", "5"),
                mode(Gamemode.mania),
                note = "note",
                main = listOf(Gamemode.osu),
            ),
        )

        val update = service.find(null)
        assertTrue(update.isComplete)
        assertEquals(emptyList<String>(), update.removed)
        assertEquals(setOf("5", "10"), update.userIds())
        assertEquals(
            PlannerBeatmap(
                id = "1",
                artist = "Artist 1",
                title = "Title 1",
                note = "note",
                mapper = "10",
                mapperName = null,
                status = BeatmapStatus.Pending,
                modes =
                    listOf(
                        PlannerMode(Gamemode.osu, listOf(PlannerNominator("5", true))),
                        PlannerMode(Gamemode.taiko, listOf(PlannerNominator("0", null), PlannerNominator("5", null))),
                        PlannerMode(Gamemode.mania, emptyList()),
                    ),
                main = listOf(Gamemode.osu),
                added = ADDED,
                updated = UPDATED,
            ),
            update.beatmaps.single(),
        )
    }

    @Test
    fun `Should only find what is in progress and who can nominate`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.user("6", "Mapper", UserGamemode(Gamemode.osu, Role.Mapper))
        planner.user("7", "Unknown")
        BeatmapStatus.entries.forEach { planner.save(beatmap(it.name, status = it)) }

        val update = service.find(null)
        assertEquals(setOf("Qualified", "Nominated", "Disqualified", "Reset", "Pending", "Unfinished"), update.beatmapIds())
        assertEquals(setOf("5"), update.userIds())
        // Nobody knows the mapper, so there is only a name
        assertEquals(setOf("Mapper"), update.beatmaps.map { it.mapperName }.toSet())
        assertEquals(setOf<String?>(null), update.beatmaps.map { it.note }.toSet())
    }

    @Test
    fun `Should find what changed`() {
        planner.user("5", "BN", nominator(Gamemode.osu))
        planner.user("6", "Other", nominator(Gamemode.osu))
        planner.save(beatmap("1", mode(Gamemode.osu, "5")))
        planner.save(beatmap("2"))
        val first = service.find(null)

        val nothing = service.find(first.token)
        assertFalse(nothing.isComplete)
        assertEquals(first.token, nothing.token)
        assertEquals(emptyList<Any>(), nothing.beatmaps + nothing.users + nothing.removed)

        planner.beatmapService.updateBeatmapNote(LOGIN, "1", "changed")
        val changed = service.find(first.token)
        assertEquals(setOf("1"), changed.beatmapIds())
        assertEquals("changed", changed.beatmaps.single().note)
        assertEquals(setOf("5"), changed.userIds())
        assertEquals(emptySet<String>(), service.find(changed.token).beatmapIds())

        planner.user("6", "Renamed")
        assertEquals(listOf("Renamed"), service.find(changed.token).users.map { it.username })
    }

    @Test
    fun `Should find what is not in progress anymore`() {
        listOf("1", "2", "3", "4").forEach { planner.save(beatmap(it)) }
        val token = service.find(null).token

        planner.beatmaps.deleteById("1")
        planner.beatmaps.update(beatmap("2", status = BeatmapStatus.Ranked))
        planner.beatmaps.update(beatmap("3", status = BeatmapStatus.Graved))
        val update = service.find(token)
        assertEquals(setOf("1", "2", "3"), update.removed.toSet())
        assertEquals(emptySet<String>(), update.beatmapIds())

        planner.beatmaps.update(beatmap("3"))
        assertEquals(setOf("3"), service.find(update.token).beatmapIds())
    }

    @Test
    fun `Should find everything when the token is not known`() {
        planner.save(beatmap())
        val token = service.find(null).token
        val run = token.substringBefore("/")
        val restarted = PlannerService(planner.beatmaps, planner.users, FeedTokens(planner.beatmaps, planner.users))

        assertTrue(restarted.find(token).isComplete)
        listOf("", "other/1/1", "$run/1", "$run/x/1", "$run/1/x", "$run/1/1/1/1").forEach {
            assertEquals(setOf("1"), service.find(it).beatmapIds(), it)
            assertTrue(service.find(it).isComplete, it)
        }
    }

    @Test
    fun `Should only give a spot to one of many`() {
        val ids = (100..120).map { "$it" }
        ids.forEach { planner.user(it, "BN $it", nominator(Gamemode.osu)) }
        planner.save(beatmap("1", mode(Gamemode.osu, "5")))
        val start = CountDownLatch(1)
        val results = ConcurrentHashMap<String, Boolean>()

        val threads =
            ids.map { id ->
                thread {
                    start.await()
                    results[id] = planner.beatmapService.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "0", id) != null
                }
            }
        planner.user("10")
        start.countDown()
        threads.forEach { it.join() }

        val winner = results.filterValues { it }.keys.single()
        assertEquals(listOf(mode(Gamemode.osu, "5", winner)), planner.stored().gamemodes)
    }

    @Test
    fun `Should not lose changes made at the same time`() {
        planner.user("10")
        planner.save(beatmap())

        val threads =
            listOf(
                thread { (1..50).forEach { planner.beatmapService.updateBeatmapNote(LOGIN, "1", "note $it") } },
                thread { planner.beatmapService.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "0", "5") },
                thread { planner.beatmapService.updateBeatmapStatus(LOGIN, "1", BeatmapStatus.Nominated) },
            )
        threads.forEach { it.join() }

        val stored = planner.stored()
        assertEquals("note 50", stored.note)
        assertEquals(BeatmapStatus.Nominated, stored.status)
        assertEquals(listOf(mode(Gamemode.osu, "5")), stored.gamemodes)
    }
}
