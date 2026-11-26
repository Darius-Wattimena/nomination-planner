package nl.greaper.bnplanner.client

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
import kotlin.concurrent.thread

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
    fun `Should not request users that are always restricted`() {
        listOf("3071175", "2697284", "6694242").forEach {
            FakeOsu.user(it, "Someone")
            assertNull(client.findUserWithId("token", it))
        }
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should find no user when osu does not know them`() {
        assertNull(client.findUserWithId("token", "4"))
    }

    @Test
    fun `Should fail to find a user when osu fails or gives an unexpected answer`() {
        listOf(400, 401, 403, 429, 500, 503).forEach {
            FakeOsu.respond("GET /api/v2/users/5?key=id", "{}", it)
            assertThrows<OsuRequestException> { client.findUserWithId("token", "5") }
        }
        listOf("", "not json", "{}").forEach {
            FakeOsu.respond("GET /api/v2/users/5?key=id", it)
            assertThrows<OsuRequestException> { client.findUserWithId("token", "5") }
        }
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
    fun `Should be able to send a body`() {
        FakeOsu.respond("POST /api/v2/path", "answer")

        assertEquals("answer", client.request("/path", HttpMethod.POST, "token", "body").body)
        assertEquals("body", FakeOsu.requests.single().body)
    }

    @Test
    fun `Should use its own token for every simultaneous call`() {
        (1..8).forEach { FakeOsu.user("$it", "User") }
        (1..8).map { id -> thread { repeat(50) { client.findUserWithId("token$id", "$id") } } }.forEach { it.join() }

        assertEquals(400, FakeOsu.requests.size)
        assertTrue(FakeOsu.requests.all { it.uri.contains("/users/${it.header("Authorization")!!.removePrefix("Bearer token")}?") })
    }
}
