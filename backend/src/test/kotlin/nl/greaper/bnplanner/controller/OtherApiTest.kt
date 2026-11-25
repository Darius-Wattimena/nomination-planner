package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.auth.OsuTokenVerifierTest.Companion.createToken
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.aiess.AiessResponse
import nl.greaper.bnplanner.model.aiess.AiessUserEvent
import nl.greaper.bnplanner.model.aiess.AiessUserEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.AiessService
import nl.greaper.bnplanner.service.OsuJobs
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

/** Contracts of everything except beatmaps */
class OtherApiTest : ApiTest() {
    @Autowired
    lateinit var jobs: OsuJobs

    // Login

    private val token = createToken(subject = "2")

    private fun assertContext(response: MockHttpServletResponse) {
        val validUntil = JSONObject(response.contentAsString).getLong("validUntilEpochMilli")
        val expected = Instant.now().plusSeconds(86400 - 300).toEpochMilli()
        assertTrue(validUntil in expected - 60_000..expected)
        response.assertJson(
            """
            {"user":{"osuId":"2","username":"Editor","gamemodes":[{"gamemode":"osu","role":"Nominator"}]},
            "accessToken":"$token","refreshToken":"refresh","validUntilEpochMilli":$validUntil,
            "permission":{"osuRole":"Nominator","roles":["VIEWER","EDITOR"]}}
            """,
        )
    }

    private fun osuKnowsEditor() {
        FakeOsu.token(token)
        FakeOsu.user("2", "Editor", """[{"id":28,"playmodes":["osu"]}]""")
    }

    private fun login(
        path: String = "/v2/auth",
        body: String = "code=",
        contentType: String? = "text/plain",
    ) = call(As.NOBODY, "POST", path, body, contentType)

    @Test
    fun `Should log in without any authorization`() {
        osuKnowsEditor()

        assertContext(login())
        assertTrue(FakeOsu.requests[0].body.contains(""""code":"code""""))
    }

    @Test
    fun `Should refresh a login without any authorization`() {
        osuKnowsEditor()

        assertContext(login("/v2/auth/refresh", "refresh="))
        assertTrue(FakeOsu.requests[0].body.contains(""""refresh_token":"refresh""""))
    }

    @Test
    fun `Should log in using a form, which is how the frontend sends it`() {
        osuKnowsEditor()

        // The server turns a form without values into 'code=', of which the last character is dropped
        listOf("/v2/auth", "/v2/auth/refresh").forEach {
            FakeOsu.requests.clear()
            val response = http("POST", it, mapOf("Content-Type" to "application/x-www-form-urlencoded"), "def502%2Bcode")

            assertEquals(200, response.statusCode())
            assertEquals(token, JSONObject(response.body()).getString("accessToken"))
            assertTrue(FakeOsu.requests[0].body.contains(""":"def502%2Bcode""""), FakeOsu.requests[0].body)
        }
    }

    @Test
    fun `SUSPECTED BUG - The last character of the code is dropped when it isn't sent as form`() {
        osuKnowsEditor()

        listOf("text/plain", "application/json").forEach {
            FakeOsu.requests.clear()
            assertEquals(200, http("POST", "/v2/auth", mapOf("Content-Type" to it), "code").statusCode())
            assertTrue(FakeOsu.requests[0].body.contains(""""code":"cod""""))
        }
    }

    @Test
    fun `Should fail to log in`() {
        listOf("/v2/auth", "/v2/auth/refresh").forEach {
            // osu refuses
            FakeOsu.respond("POST /oauth/token", "{}", 401)
            login(it).assertEmpty(400)
            // osu fails
            FakeOsu.respond("POST /oauth/token", "{}", 500)
            login(it).assertEmpty(400)
            // osu returns something unreadable
            FakeOsu.token("unreadable")
            login(it).assertEmpty(400)
        }
        login(body = "=").assertEmpty(400)
    }

    @Test
    fun `Should refuse to log in without a body`() {
        assertEquals(400, login(body = "").status)
        assertEquals(400, login("/v2/auth/refresh", "").status)
        assertEquals(405, call(As.VIEWER, "GET", "/v2/auth").status)
    }

    // Users

    @Test
    fun `Should search users`() {
        val viewer = """{"osuId":"1","username":"Viewer","gamemodes":[{"gamemode":"osu","role":"Mapper"}]}"""
        val editor = """{"osuId":"2","username":"Editor","gamemodes":[{"gamemode":"osu","role":"Nominator"}]}"""
        val admin = """{"osuId":"3","username":"Admin","gamemodes":[{"gamemode":"mania","role":"NominationAssessment"}]}"""

        get("/v2/user/search").assertJson("[$admin,$editor]")
        get("/v2/user/search?username=DI").assertJson("[$editor]")
        get("/v2/user/search?gamemodes=mania,taiko").assertJson("[$admin]")
        get("/v2/user/search?gamemodes=mania&gamemodes=osu").assertJson("[$admin,$editor]")
        get("/v2/user/search?roles=Mapper").assertJson("[$viewer]")
        get("/v2/user/search?roles=Mapper,Nominator").assertJson("[$editor,$viewer]")
        get("/v2/user/search?username=&gamemodes=&roles=").assertJson("[$admin,$editor]")
        get("/v2/user/search?username=nobody").assertJson("[]")
        assertEquals(400, get("/v2/user/search?roles=Other").status)
        // Either separated or repeated, not both
        assertEquals(400, get("/v2/user/search?roles=Mapper,Nominator&roles=Probation").status)
    }

    @Test
    fun `SUSPECTED BUG - Searching users returns at most 20`() {
        (10..40).forEach { users.addInMemoryUser(User("$it", "User $it", listOf(UserGamemode(Gamemode.osu, Role.Nominator)))) }

        val found = JSONArray(get("/v2/user/search").contentAsString)
        assertEquals(20, found.length())
        assertEquals("Admin", found.getJSONObject(0).getString("username"))
        assertEquals("User 27", found.getJSONObject(19).getString("username"))
    }

    // Profile

    @Test
    fun `Should find the pairing of a user`() {
        beatmaps.insertOne(beatmap("1", mode(Gamemode.osu, "2", "3"), mode(Gamemode.mania, "0", "2")))
        beatmaps.insertOne(beatmap("2", mode(Gamemode.osu, "1", "2"), status = BeatmapStatus.Ranked))
        beatmaps.insertOne(beatmap("3", mode(Gamemode.osu, "2", "3"), status = BeatmapStatus.Graved))

        get("/v2/profile/2/pairing").assertJson(
            """
            [{"name":"Admin","pairingPending":1,"pairingRanked":0,"pairingGraved":1},
            {"name":"Viewer","pairingPending":0,"pairingRanked":1,"pairingGraved":0}]
            """,
        )
        get("/v2/profile/99/pairing").assertJson("[]")
    }

    // Aiess

    private fun beatmapEvent(
        status: String = "Nominated",
        user: String = """"userId":"2","userName":"Editor",""",
        modes: String = """["osu"]""",
    ) = call(
        As.BOT,
        "POST",
        "/v2/aiess/event/beatmap",
        """
        {"beatmapSetId":"1","artist":"Artist 1","title":"Title 1","creatorName":"Mapper","creatorId":"10","status":"$status",
        $user"modes":$modes,"unknown":true}
        """,
    )

    @Test
    fun `Should process a beatmap event`() {
        beatmapEvent().assertJson("""{"result":true}""")

        val stored = beatmaps.findById("1")!!
        assertEquals(listOf("Artist 1", "Title 1", "Mapper", "10"), listOf(stored.artist, stored.title, stored.mapper, stored.mapperId))
        assertEquals(BeatmapStatus.Nominated, stored.status)
        assertEquals(listOf(mode(Gamemode.osu, "2", firstNominated = true)), stored.gamemodes)
        assertEquals("Mapper", users.findUser("10")!!.username)
    }

    @Test
    fun `Should accept a numeric id and null user in a beatmap event`() {
        beatmapEvent("Ranked", """"userId":null,"userName":null,""", "[]").assertJson("""{"result":true}""")
        beatmapEvent("Qualified", """"userId":2,"userName":"Editor",""").assertJson("""{"result":true}""")
        beatmapEvent("Nominated", "").assertJson("""{"result":true}""")
    }

    @Test
    fun `Should fail a beatmap event without modes`() {
        beatmapEvent(modes = "[]").assertJson("""{"result":false,"error":"No modes are provided"}""", 400)
    }

    @Test
    fun `Should refuse an unreadable beatmap event`() {
        assertEquals(400, beatmapEvent("5x").status)
        assertEquals(400, beatmapEvent(modes = """["catch"]""").status)
        assertEquals(400, beatmapEvent(modes = "null").status)
    }

    @Test
    fun `SUSPECTED BUG - A number as status of a beatmap event is used as index and not as priority`() {
        // 6 is the priority of ranked, but the index of graved
        call(
            As.BOT,
            "POST",
            "/v2/aiess/event/beatmap",
            """{"beatmapSetId":"1","artist":"","title":"","creatorName":"","creatorId":"10","status":6,"modes":[]}""",
        ).assertJson("""{"result":true}""")
        assertEquals(BeatmapStatus.Graved, beatmaps.findById("1")!!.status)
    }

    private fun userEvent(
        type: String = "add",
        group: String = "\"28\"",
        mode: String = "taiko",
    ) = call(
        As.BOT,
        "POST",
        "/v2/aiess/event/user",
        """{"type":"$type","userId":"2","userName":"Editor","groupId":$group,"groupMode":"$mode"}""",
    )

    @Test
    fun `Should process a user event`() {
        userEvent().assertJson("""{"result":true}""")
        userEvent(group = "7").assertJson("""{"result":true}""")
        assertEquals(
            listOf(
                UserGamemode(Gamemode.osu, Role.Nominator),
                UserGamemode(Gamemode.taiko, Role.Nominator),
                UserGamemode(Gamemode.taiko, Role.NominationAssessment),
            ),
            users.findUser("2")!!.gamemodes,
        )

        userEvent("remove").assertJson("""{"result":true}""")
        assertEquals(
            listOf(UserGamemode(Gamemode.osu, Role.Nominator), UserGamemode(Gamemode.taiko, Role.NominationAssessment)),
            users.findUser("2")!!.gamemodes,
        )
        assertEquals(400, userEvent("move").status)
        assertEquals(400, userEvent(mode = "catch").status)
    }

    @Test
    fun `Should answer a failed user event as bad request`() {
        // Never the case at the moment, as every user event succeeds
        val service = mock<AiessService> { on { processAiessUserEvent(any()) } doReturn AiessResponse(false, "failed") }
        val response = AiessController(service).userEvent(AiessUserEvent(AiessUserEventType.add, "2", "Editor", "28", Gamemode.osu))

        assertEquals(400, response.statusCode.value())
        assertEquals(AiessResponse(false, "failed"), response.body)
    }

    // Admin

    private fun assertSynced(
        amount: Int,
        response: MockHttpServletResponse,
    ) {
        val duration = JSONObject(response.contentAsString).getLong("duration")
        response.assertJson("""{"duration":$duration,"totalSynced":$amount}""")
    }

    private fun sync(
        by: As,
        path: String,
        body: String? = null,
    ) = call(by, "POST", "/v2/admin/sync/$path", body)

    private fun saveEveryStatus() =
        BeatmapStatus.entries.forEach {
            beatmaps.insertOne(beatmap("${it.ordinal + 1}", mode(Gamemode.osu, "2", "3"), status = it, mapperId = "1"))
            FakeOsu.beatmap("${it.ordinal + 1}", title = "Synced")
        }

    private fun synced() =
        beatmaps
            .list()
            .filter { it.title == "Synced" }
            .map { it.osuId }
            .toSet()

    @Test
    fun `Should sync pending beatmaps`() {
        saveEveryStatus()

        assertSynced(6, sync(As.ADMIN, "beatmaps/pending"))
        assertEquals(setOf("1", "2", "3", "4", "5", "8"), synced())
        assertEquals(6, sleeper.sleeps.size)
        assertEquals(As.ADMIN.header, FakeOsu.requests.first().header("Authorization"))
    }

    @Test
    fun `Should sync pending beatmaps by status`() {
        saveEveryStatus()

        assertSynced(2, sync(As.ADMIN, "beatmaps/pending?status=Qualified,Reset"))
        assertEquals(setOf("1", "4"), synced())
        assertEquals(400, sync(As.ADMIN, "beatmaps/pending?status=Other").status)
    }

    @Test
    fun `Should sync pending beatmaps in the background`() {
        saveEveryStatus()

        assertSynced(2, sync(As.ADMIN, "beatmaps/pending?status=Qualified,Reset&background=true"))
        jobs.finish()
        assertEquals(setOf("1", "4"), synced())
        get("/v2/admin/sync/waiting", As.ADMIN).assertJson("0")
    }

    // Live updates

    private fun waitForChanges(since: String): Pair<Long, JSONObject> {
        FakeOsu.respond("GET /api/v2/me", """{"id":1,"username":"Viewer"}""")
        val start = System.currentTimeMillis()
        val response = http("GET", "/v2/planner/wait?since=$since", mapOf("Authorization" to As.VIEWER.header!!))
        assertEquals(200, response.statusCode(), response.body())
        return (System.currentTimeMillis() - start) to JSONObject(response.body())
    }

    @Test
    fun `Should answer when nothing changed for a while`() {
        val token = JSONObject(get("/v2/planner").contentAsString).getString("token")

        val (duration, update) = waitForChanges(token)
        assertTrue(duration >= 350, "$duration")
        assertEquals(token, update.getString("token"))
        assertEquals(0, update.getJSONArray("beatmaps").length())
    }

    @Test
    fun `Should answer as soon as something changed`() {
        beatmaps.insertOne(beatmap("1", mapperId = "1"))
        val token = JSONObject(get("/v2/planner").contentAsString).getString("token")
        Thread {
            Thread.sleep(100)
            beatmaps.update(beatmap("1", note = "changed", mapperId = "1"))
        }.start()

        val (duration, update) = waitForChanges(token)
        assertTrue(duration < 350, "$duration")
        assertEquals("changed", update.getJSONArray("beatmaps").getJSONObject(0).getString("note"))
    }

    @Test
    fun `Should sync a single beatmap`() {
        saveEveryStatus()

        assertSynced(1, sync(As.EDITOR, "beatmaps/single?id=5"))
        assertEquals(setOf("5"), synced())
        assertEquals(400, sync(As.EDITOR, "beatmaps/single?id=a").status)
        assertEquals(400, sync(As.EDITOR, "beatmaps/single").status)
    }

    @Test
    fun `Should sync graved beatmaps without an answer`() {
        saveEveryStatus()

        sync(As.DEVELOPER, "beatmaps/graveyard").assertEmpty()
        assertEquals(setOf("7"), synced())
    }

    @Test
    fun `Should sync all users`() {
        saveEveryStatus()
        listOf("1", "2", "3").forEach { FakeOsu.user(it, "Synced") }

        assertSynced(3, sync(As.DEVELOPER, "users"))
        assertEquals(listOf("Synced", "Synced", "Synced"), users.list().map { it.username })
        assertEquals(As.DEVELOPER.header, FakeOsu.requests.first().header("Authorization"))
    }

    @Test
    fun `Should sync some users`() {
        listOf("1", "4").forEach { FakeOsu.user(it, "Synced $it") }

        assertSynced(2, sync(As.ADMIN, "users/ids", """["1","4"]"""))
        assertEquals(listOf("Viewer", "Synced 4"), listOf("1", "4").map { users.findUser(it)!!.username })
        assertSynced(1, sync(As.ADMIN, "users/ids?force=false", """["1"]"""))
        assertEquals("Viewer", users.findUser("1")!!.username)
        assertSynced(1, sync(As.ADMIN, "users/ids?force=true", "[1]"))
        assertEquals("Synced 1", users.findUser("1")!!.username)
        assertEquals(400, sync(As.ADMIN, "users/ids").status)
    }
}
