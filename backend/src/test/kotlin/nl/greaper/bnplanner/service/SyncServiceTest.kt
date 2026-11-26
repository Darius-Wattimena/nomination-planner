package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncServiceTest {
    private val planner = Planner()
    private val service = planner.syncService

    private fun assertSynced(
        amount: Int,
        info: SyncService.SyncInfo,
    ) {
        assertEquals(amount, info.totalSynced)
        assertTrue(info.duration in 0..60_000)
    }

    /** Every call waits between 1 and 2 seconds, to avoid osu! blocking us */
    private fun assertWaited(times: Int) {
        assertEquals(times, planner.sleeps.size)
        assertTrue(planner.sleeps.all { it in 1_000..1_999 }, "${planner.sleeps}")
    }

    private fun calls() = FakeOsu.calls().toSet()

    private fun saveEveryStatus() =
        BeatmapStatus.entries.forEach {
            planner.save(beatmap("${it.ordinal + 1}", status = it))
            FakeOsu.beatmap("${it.ordinal + 1}", title = "Synced")
        }

    private fun synced() =
        planner.beatmaps
            .list()
            .filter { it.title == "Synced" }
            .map { it.osuId }
            .toSet()

    @Test
    fun `Should sync a single beatmap`() {
        saveEveryStatus()

        assertSynced(1, service.syncSingleBeatmap(TOKEN, "5"))
        assertEquals(setOf("5"), synced())
        assertWaited(0)
    }

    @Test
    fun `SUSPECTED BUG - An unknown beatmap counts as synced`() {
        assertSynced(1, service.syncSingleBeatmap(TOKEN, "5"))
        assertEquals(emptySet<String>(), calls())
    }

    @Test
    fun `Should only sync unknown and restricted users when not forced`() {
        planner.user("10")
        planner.users.saveUser(User("12", "RESTRICTED", emptyList(), restricted = true))
        planner.users.saveUser(User("13", "Fine", emptyList(), restricted = false))
        listOf("10", "12", "13", "14").forEach { FakeOsu.user(it, "Synced $it") }

        assertSynced(4, service.syncUsers(TOKEN, setOf("10", "12", "13", "14")))
        assertEquals(setOf("GET /api/v2/users/12?key=id", "GET /api/v2/users/14?key=id"), calls())
        assertEquals("Synced 12", planner.users.findUser("12")!!.username)
        assertWaited(2)
    }

    @Test
    fun `Should sync every user when forced`() {
        planner.user("10")
        listOf("10", "14").forEach { FakeOsu.user(it, "Synced $it") }

        assertSynced(2, service.syncUsers(TOKEN, setOf("10", "14"), force = true))
        assertEquals(
            setOf("Synced 10", "Synced 14"),
            planner.users
                .list()
                .map { it.username }
                .toSet(),
        )
    }

    @Test
    fun `Should keep a user when osu is unavailable during a forced sync`() {
        val user = planner.user("10", "BN", Planner.nominator(Gamemode.osu))
        FakeOsu.respond("GET /api/v2/users/10?key=id", "{}", status = 503)
        service.syncUsers(TOKEN, setOf("10"), force = true)

        assertEquals(listOf(user), planner.users.list())
    }

    @Test
    fun `Should remove the gamemodes of a restricted user during a forced sync`() {
        planner.user("10", "BN", Planner.nominator(Gamemode.osu))
        service.syncUsers(TOKEN, setOf("10"), force = true)

        assertEquals(listOf(User("10", "RESTRICTED", emptyList(), true)), planner.users.list())
    }
}
