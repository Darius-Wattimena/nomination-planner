package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncServiceTest {
    private val planner = Planner()
    private val service = planner.syncService

    @BeforeEach
    fun token() = FakeOsu.token("own")

    private fun calls() = FakeOsu.calls().filter { it.startsWith("GET") }.toSet()

    private fun syncUsers(
        users: Set<String>,
        force: Boolean = false,
    ): Int = service.syncUsers(users, force).queued.also { planner.osuJobs.finish() }

    @Test
    fun `Should sync the pending beatmaps with the requested statuses`() {
        BeatmapStatus.entries.forEach {
            planner.save(beatmap("${it.ordinal + 1}", status = it))
            FakeOsu.beatmap("${it.ordinal + 1}", title = "Synced")
        }

        assertEquals(2, service.syncPendingBeatmaps(setOf(BeatmapStatus.Qualified, BeatmapStatus.Reset)).queued)
        planner.osuJobs.finish()

        assertEquals(
            setOf("1", "4"),
            planner.beatmaps
                .list()
                .filter { it.title == "Synced" }
                .map { it.osuId }
                .toSet(),
        )
        assertEquals(6, service.syncPendingBeatmaps(emptySet()).queued)
        planner.osuJobs.finish()
        assertEquals(0, service.findWaiting())
    }

    @Test
    fun `Should only sync unknown and restricted users when not forced`() {
        planner.user("10")
        planner.users.saveUser(User("12", "RESTRICTED", emptyList(), restricted = true))
        planner.users.saveUser(User("13", "Fine", emptyList(), restricted = false))
        listOf("10", "12", "13", "14").forEach { FakeOsu.user(it, "Synced $it") }

        assertEquals(2, syncUsers(setOf("10", "12", "13", "14")))
        assertEquals(setOf("GET /api/v2/users/12?key=id", "GET /api/v2/users/14?key=id"), calls())
        assertEquals("Synced 12", planner.users.findUser("12")!!.username)
        // Using the planner's own login with osu!
        assertEquals(true, FakeOsu.requests.filter { it.method == "GET" }.all { it.header("Authorization") == "Bearer own" })
        // Only logged, not sent to Discord
        assertEquals(emptyList<Any>(), planner.discord.sent)
    }

    @Test
    fun `Should sync every user when forced`() {
        planner.user("10")
        listOf("10", "14").forEach { FakeOsu.user(it, "Synced $it") }

        assertEquals(2, syncUsers(setOf("10", "14"), force = true))
        assertEquals(
            setOf("Synced 10", "Synced 14"),
            planner.users
                .list()
                .map { it.username }
                .toSet(),
        )
        assertEquals(0, service.findWaiting())
    }

    @Test
    fun `Should keep a user when osu is unavailable during a forced sync`() {
        val user = planner.user("10", "BN", Planner.nominator(Gamemode.osu))
        FakeOsu.respond("GET /api/v2/users/10?key=id", "{}", status = 503)
        syncUsers(setOf("10"), force = true)

        assertEquals(listOf(user), planner.users.list())
    }

    @Test
    fun `Should remove the gamemodes of a restricted user during a forced sync`() {
        planner.user("10", "BN", Planner.nominator(Gamemode.osu))
        syncUsers(setOf("10"), force = true)

        assertEquals(listOf(User("10", "RESTRICTED", emptyList(), true)), planner.users.list())
        assertEquals(emptyList<Any>(), planner.discord.sent)
    }
}
