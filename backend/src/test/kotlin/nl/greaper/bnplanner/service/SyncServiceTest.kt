package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.client.OsuUnavailableException
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

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

    private val since = LocalDate.now(ZoneOffset.UTC).minusDays(30)

    private fun nomination(
        id: Long,
        beatmapId: String,
        userId: String,
        vararg modes: String,
        daysAgo: Long = 1,
    ) = """{"id":$id,"type":"nominate","comment":{"modes":[${modes.joinToString { "\"$it\"" }}]},"user_id":$userId,
        |"created_at":"${Instant.now().minus(daysAgo, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)}","beatmapset":{"id":$beatmapId}}
        """.trimMargin()

    private fun nominations(
        page: Int,
        vararg events: String,
    ) = FakeOsu.respond(
        "GET /api/v2/beatmapsets/events?types%5B%5D=nominate&min_date=$since&limit=50&page=$page",
        """{"events":[${events.joinToString()}]}""",
    )

    private fun importMissing() = service.importMissingBeatmaps(30).also { planner.osuJobs.finish() }

    @Test
    fun `Should import the beatmaps nominated on osu that are not on the planner`() {
        planner.save(beatmap("5", title = "Planned"))
        listOf("20", "21", "22").forEach { FakeOsu.user(it, "BN $it") }
        FakeOsu.user("10", "Mapper")
        nominations(
            1,
            nomination(9, "6", "21", "taiko"),
            nomination(8, "6", "20", "osu"),
            nomination(7, "5", "20", "osu"),
            nomination(6, "7", "22", "osu"),
        )
        // Older than asked for, where it stops
        nominations(2, nomination(5, "7", "21", "osu"), nomination(4, "8", "21", "osu", daysAgo = 40))
        FakeOsu.beatmap(
            "6",
            nominations = """[{"rulesets":["osu"],"reset":false,"user_id":20},{"rulesets":["taiko"],"reset":false,"user_id":21}]""",
        )
        // Reset since
        FakeOsu.beatmap("7")
        FakeOsu.beatmap("8", ranked = -2)

        assertEquals(true, importMissing().started)

        val nominated = planner.beatmaps.findById("6") ?: error(FakeOsu.calls().joinToString(" | "))
        assertEquals(BeatmapStatus.Nominated, nominated.status)
        assertEquals(
            listOf(mode(Gamemode.osu, "20", firstNominated = true), mode(Gamemode.taiko, "21", firstNominated = true)),
            nominated.gamemodes,
        )
        val reset = planner.stored("7")
        assertEquals(BeatmapStatus.Reset, reset.status)
        assertEquals(listOf(mode(Gamemode.osu, "21", "22")), reset.gamemodes)
        // Already there, and older than asked for
        assertEquals("Planned", planner.stored("5").title)
        assertNull(planner.beatmaps.findById("8"))
        assertEquals(false, FakeOsu.calls().any { it.endsWith("beatmapsets/events?types%5B%5D=nominate&min_date=$since&limit=50&page=3") })
        assertEquals(
            setOf("/api/v2/beatmapsets/6", "/api/v2/beatmapsets/7"),
            FakeOsu.requests
                .map { it.uri }
                .filter { it.matches(Regex("/api/v2/beatmapsets/[0-9]+")) }
                .toSet(),
        )

        val added = planner.events.findNewest("7", 10).single()
        assertEquals(BeatmapEventType.Added, added.type)
        assertNull(added.userId)
        assertEquals(BeatmapStatus.Reset, added.status)
        // Only logged, not sent to Discord
        assertEquals(emptyList<Any>(), planner.discord.sent)
        assertEquals(0, service.findWaiting())
    }

    @Test
    fun `Should leave out a beatmap that was imported meanwhile`() {
        FakeOsu.user("20", "BN")
        FakeOsu.user("10", "Mapper")
        nominations(1, nomination(1, "6", "20", "osu"))
        nominations(2)
        FakeOsu.beatmap(
            "6",
            ranked = 1,
            rankedDate = "2024-05-06T07:08:09Z",
            nominations = """[{"rulesets":["osu"],"reset":false,"user_id":20}]""",
        )

        importMissing()
        assertEquals(BeatmapStatus.Ranked, planner.stored("6").status)
        FakeOsu.requests.clear()
        importMissing()

        assertEquals(
            listOf(1, 2).map { "/api/v2/beatmapsets/events?types%5B%5D=nominate&min_date=$since&limit=50&page=$it" },
            FakeOsu.requests.map { it.uri }.filter { it.startsWith("/api") },
        )
    }

    private fun group(
        id: String,
        vararg members: String,
    ) = FakeOsu.respond(
        "GET /groups/$id",
        """<html><script id="json-users" type="application/json">[${members.joinToString {
            """{"id":$it,"username":"U$it"}"""
        }}]</script></html>""",
    )

    @Test
    fun `Should sync everyone in the groups on osu and who left them`() {
        planner.user("30", "Left", Planner.nominator(Gamemode.osu))
        planner.user("31", "Mapper", UserGamemode(Gamemode.osu, Role.Mapper))
        group("28", "20", "21")
        group("32", "22")
        group("7", "21", "23")
        listOf("20", "21", "22", "23", "30").forEach { FakeOsu.user(it, "Synced $it") }

        assertEquals(5, service.syncGroupMembers().queued)
        planner.osuJobs.finish()

        assertEquals(
            setOf("20", "21", "22", "23", "30")
                .map {
                    "GET /api/v2/users/$it?key=id"
                }.toSet(),
            calls().filter { it.contains("/users/") }.toSet(),
        )
        assertEquals("Synced 30", planner.users.findUser("30")!!.username)
    }

    @Test
    fun `Should not sync the groups when osu doesn't tell who is in them`() {
        group("28", "20")
        FakeOsu.respond("GET /groups/32", "<html>Changed</html>")

        assertThrows<OsuUnavailableException> { service.syncGroupMembers() }
        assertEquals(0, service.findWaiting())
    }
}
