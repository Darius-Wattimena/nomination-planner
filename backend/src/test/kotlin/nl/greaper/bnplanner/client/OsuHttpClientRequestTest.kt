package nl.greaper.bnplanner.client

import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.Me
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import java.time.Instant

/** The exact requests osu! gets, by sending them to a local server */
class OsuHttpClientRequestTest {
    private val client = Planner.osuClient()

    @BeforeEach
    fun setup() = FakeOsu.reset()

    private fun assertTokenRequest(expectedBody: String) {
        val request = FakeOsu.requests.single()
        assertEquals("POST /oauth/token", "${request.method} ${request.uri}")
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("Authorization"))
        JSONAssert.assertEquals(expectedBody, request.body, JSONCompareMode.STRICT)
    }

    private val tokenResponse = AuthToken("Bearer", 86400, "made-up-access", "made-up-refresh")

    @Test
    fun `Should use osu by default`() {
        assertEquals("https://osu.ppy.sh", OsuProperties("14229", "", "").baseUrl)
    }

    @Test
    fun `Should get a token`() {
        FakeOsu.token("made-up-access", "made-up-refresh")

        assertEquals(tokenResponse, client.getToken("code")!!.body)
        assertTokenRequest(
            """
            {"client_id":14229,"client_secret":"made-up-secret","code":"code","grant_type":"authorization_code",
            "redirect_uri":"http://localhost:3000/login"}
            """,
        )
    }

    @Test
    fun `Should ignore unknown details of a token`() {
        FakeOsu.respond(
            "POST /oauth/token",
            """{"token_type":"Bearer","expires_in":86400,"access_token":"a","refresh_token":"r","scope":"public"}""",
        )

        assertEquals(AuthToken("Bearer", 86400, "a", "r"), client.getToken("code")!!.body)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 429, 500])
    fun `Should get no token when osu refuses`(status: Int) {
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_grant"}""", status)

        assertNull(client.getToken("code"))
    }

    @Test
    fun `Should refresh a token`() {
        FakeOsu.token("made-up-access", "made-up-refresh")

        assertEquals(tokenResponse, client.refreshToken("old")!!.body)
        assertTokenRequest(
            """{"grant_type":"refresh_token","client_id":14229,"client_secret":"made-up-secret","refresh_token":"old"}""",
        )
    }

    @Test
    fun `Should refresh no token when osu does not accept it`() {
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_request"}""", 401)

        assertNull(client.refreshToken("old"))
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 429])
    fun `Should fail to refresh a token on another error`(status: Int) {
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_request"}""", status)

        assertThrows<HttpClientErrorException> { client.refreshToken("old") }
    }

    @Test
    fun `Should fail to refresh a token when osu fails`() {
        FakeOsu.respond("POST /oauth/token", "{}", 500)

        assertThrows<RestClientException> { client.refreshToken("old") }
    }

    @Test
    fun `Should find the owner of a token`() {
        FakeOsu.respond("GET /api/v2/me", """{"id":123,"username":"Someone","groups":[],"statistics":{"pp":1.5}}""")

        assertEquals("123", client.findTokenOwnerId("token"))
        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))
        assertEquals("", FakeOsu.requests.single().body)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `Should find no owner when osu refuses the token`(status: Int) {
        FakeOsu.respond("GET /api/v2/me", "{}", status)

        assertNull(client.findTokenOwnerId("token"))
    }

    @Test
    fun `Should find no owner without a body`() {
        FakeOsu.respond("GET /api/v2/me", "", 204)

        assertNull(client.findTokenOwnerId("token"))
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 429, 500, 503])
    fun `Should be unavailable when osu gives no clear answer`(status: Int) {
        FakeOsu.respond("GET /api/v2/me", "{}", status)

        val ex = assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }
        assertEquals("Could not verify the token with osu!", ex.message)
    }

    @Test
    fun `Should be unavailable when nothing is listening`() {
        val client = OsuHttpClient(OsuProperties("14229", "", "", "http://127.0.0.1:1"), client.objectMapper)

        assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }
    }

    @Test
    fun `Should find a user`() {
        FakeOsu.user("5", "BN", """[{"id":28,"playmodes":["osu"],"identifier":"bng"},{"id":16}]""")

        assertEquals(
            Me("5", "BN", listOf(MeGroup("28", listOf("osu")), MeGroup("16", null))),
            client.findUserWithId("token", "5"),
        )
        assertEquals(listOf("GET /api/v2/users/5?key=id"), FakeOsu.calls())
        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))
        assertEquals("", FakeOsu.requests.single().body)
    }

    @Test
    fun `Should send the token as is when it already includes bearer`() {
        FakeOsu.user("5", "BN")
        client.findUserWithId("Bearer token", "5", includeBearer = false)

        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should not request users that are always restricted`() {
        listOf("3071175", "2697284", "6694242").forEach {
            FakeOsu.user(it, "Someone")
            assertNull(client.findUserWithId("token", it))
        }
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should find no user when osu fails or gives an unexpected answer`() {
        FakeOsu.respond("GET /api/v2/users/5?key=id", "{}", 500)
        FakeOsu.respond("GET /api/v2/users/6?key=id", "", 200)
        FakeOsu.respond("GET /api/v2/users/7?key=id", "not json")
        FakeOsu.respond("GET /api/v2/users/8?key=id", "{}")

        listOf("4", "5", "6", "7", "8").forEach { assertNull(client.findUserWithId("token", it)) }
    }

    private val beatmapSet =
        BeatmapSet(
            id = "1",
            title = "Title 1",
            artist = "Artist 1",
            creator = "Mapper",
            user_id = "10",
            currentNominations = listOf(BeatmapSet.CurrentNomination(listOf("osu", "taiko"), false, 5)),
            ranked = 1,
            ranked_date = Instant.parse("2024-04-04T04:04:04Z"),
            last_updated = Instant.parse("2024-05-06T07:08:09Z"),
            beatmaps = listOf(BeatmapSet.Beatmap(100, 10, Gamemode.osu, "Hard")),
        )

    private fun respondBeatmap() =
        FakeOsu.beatmap(
            "1",
            ranked = 1,
            rankedDate = "2024-04-04T04:04:04+00:00",
            nominations = """[{"beatmapset_id":1,"rulesets":["osu","taiko"],"reset":false,"user_id":5}]""",
        )

    @Test
    fun `Should find a beatmap`() {
        respondBeatmap()

        assertEquals(beatmapSet, client.findBeatmapWithId("token", "1"))
        assertEquals(listOf("GET /api/v2/beatmapsets/1"), FakeOsu.calls())
        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))

        client.findBeatmapWithId("Bearer other", "1", includeBearer = false)
        assertEquals("Bearer other", FakeOsu.requests.last().header("Authorization"))
    }

    @Test
    fun `Should find a beatmap with missing details`() {
        FakeOsu.respond(
            "GET /api/v2/beatmapsets/1",
            """{"id":1,"title":"t","artist":"a","creator":"c","user_id":10,"ranked":-2,"last_updated":"2024-05-06T07:08:09Z"}""",
        )

        assertEquals(
            BeatmapSet("1", "t", "a", "c", "10", ranked = -2, last_updated = Instant.parse("2024-05-06T07:08:09Z")),
            client.findBeatmapWithId("token", "1"),
        )
    }

    @Test
    fun `Should find no beatmap when osu fails or gives an unexpected answer`() {
        FakeOsu.respond("GET /api/v2/beatmapsets/5", "{}", 500)
        FakeOsu.respond("GET /api/v2/beatmapsets/6", "", 200)
        FakeOsu.respond("GET /api/v2/beatmapsets/7", "not json")
        FakeOsu.respond("GET /api/v2/beatmapsets/8", "{}")

        listOf("4", "5", "6", "7", "8").forEach { assertNull(client.findBeatmapWithId("token", it)) }
    }

    @Test
    fun `Should find the ranked beatmaps of a mapper`() {
        respondBeatmap()
        FakeOsu.respond(
            "GET /api/v2/users/10/beatmapsets/ranked",
            """[{"id":1,"title":"t","artist":"a","creator":"c","user_id":10,"ranked":1,"last_updated":"2024-05-06T07:08:09Z"}]""",
        )

        assertEquals(listOf("1"), client.findBeatmapsByMapper(10, "token").map { it.id })
        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))
        client.findBeatmapsByMapper(10, "Bearer other", includeBearer = false)
        assertEquals("Bearer other", FakeOsu.requests.last().header("Authorization"))
    }

    @Test
    fun `Should find no ranked beatmaps when osu fails or gives an unexpected answer`() {
        FakeOsu.respond("GET /api/v2/users/5/beatmapsets/ranked", "", 200)
        FakeOsu.respond("GET /api/v2/users/6/beatmapsets/ranked", "not json")

        listOf(4, 5, 6).forEach { assertTrue(client.findBeatmapsByMapper(it, "token").isEmpty()) }
    }

    @Test
    fun `Should be able to send a body`() {
        FakeOsu.respond("POST /api/v2/path", "answer")

        assertEquals("answer", client.request("/path", HttpMethod.POST, "token", "body", includeBearer = true).body)
        assertEquals("body", FakeOsu.requests.single().body)
    }

    @Test
    fun `SUSPECTED BUG - Headers are shared between calls, so simultaneous calls can use the token of each other`() {
        val headers = OsuHttpClient::class.java.getDeclaredField("headers").apply { isAccessible = true }
        val before = headers.get(client)
        FakeOsu.user("5", "BN")
        client.findUserWithId("first", "5")

        assertTrue(before === headers.get(client))
        assertTrue(headers.get(client).toString().contains("Bearer first"))
        client.findUserWithId("second", "5")
        assertTrue(headers.get(client).toString().contains("Bearer second"))
        assertEquals(listOf("Bearer first", "Bearer second"), FakeOsu.requests.map { it.header("Authorization") })
    }
}
