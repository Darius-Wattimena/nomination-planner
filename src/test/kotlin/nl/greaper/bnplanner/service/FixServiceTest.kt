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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixServiceTest {
    private val planner = Planner()
    private val service = planner.fixService

    private fun assertSynced(
        amount: Int,
        info: FixService.SyncInfo,
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
    fun `Should sync all beatmaps of a page`() {
        saveEveryStatus()

        assertSynced(6, service.syncAllBeatmaps("Bearer $TOKEN", BeatmapPage.PENDING))
        assertEquals(setOf("1", "2", "3", "4", "5", "8"), synced())
        assertWaited(6)

        assertSynced(1, service.syncAllBeatmaps("Bearer $TOKEN", BeatmapPage.GRAVEYARD))
        assertEquals(setOf("1", "2", "3", "4", "5", "8", "7"), synced())
        assertTrue(FakeOsu.requests.all { it.header("Authorization") == "Bearer $TOKEN" })
    }

    @Test
    fun `Should sync beatmaps by status`() {
        saveEveryStatus()

        assertSynced(2, service.syncBeatmaps(TOKEN, BeatmapPage.PENDING, setOf(BeatmapStatus.Qualified, BeatmapStatus.Reset)))
        assertEquals(setOf("1", "4"), synced())
    }

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
    fun `Should sync all users except restricted ones`() {
        planner.user("10")
        planner.user("11")
        planner.users.saveUser(User("12", "RESTRICTED", emptyList(), restricted = true))
        planner.users.saveUser(User("13", "Fine", emptyList(), restricted = false))
        planner.save(beatmap("1", mode(Gamemode.osu, "11", "12"), mode(Gamemode.mania, "13", "14")))
        planner.save(beatmap("2", mode(Gamemode.osu, "11", "11")))
        listOf("10", "11", "13", "14").forEach { FakeOsu.user(it, "Synced $it") }

        assertSynced(4, service.syncAllUsers("Bearer $TOKEN"))
        assertEquals(listOf("10", "11", "13", "14").map { "GET /api/v2/users/$it?key=id" }.toSet(), calls())
        assertEquals("Bearer $TOKEN", FakeOsu.requests.first().header("Authorization"))
        assertEquals("RESTRICTED", planner.users.findUser("12")!!.username)
        assertEquals("Synced 14", planner.users.findUser("14")!!.username)
        assertWaited(4)
    }

    @Test
    fun `SUSPECTED BUG - Syncing all users also saves the missing nominator as a restricted user`() {
        planner.user("10")
        FakeOsu.user("10", "Synced")
        planner.save(beatmap("1", mode(Gamemode.osu)))

        assertSynced(2, service.syncAllUsers(TOKEN))
        assertEquals(setOf("GET /api/v2/users/0?key=id", "GET /api/v2/users/10?key=id"), calls())
        assertEquals("RESTRICTED", planner.users.findUser("0")!!.username)
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
    fun `SUSPECTED BUG - A forced sync deletes a user first and loses their gamemodes when osu is unavailable`() {
        planner.user("10", "BN", Planner.nominator(Gamemode.osu))
        service.syncUsers(TOKEN, setOf("10"), force = true)

        assertEquals(listOf(User("10", "RESTRICTED", emptyList(), true)), planner.users.list())
        assertNull(planner.users.usersByGamemode[Gamemode.osu]!!.find { it == "10" })
    }
}
