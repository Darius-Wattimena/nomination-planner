package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
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

    // Users

    // Profile

    @Test
    fun `Should find the pairing of a user`() {
        beatmaps.insertOne(beatmap("1", mode(Gamemode.osu, "2", "3"), mode(Gamemode.mania, "0", "2")))
        beatmaps.insertOne(beatmap("2", mode(Gamemode.osu, "1", "2"), status = BeatmapStatus.Ranked))
        beatmaps.insertOne(beatmap("3", mode(Gamemode.osu, "2", "3"), status = BeatmapStatus.Graved))

        get("/v2/profile/2/pairing").assertJson(
            """
            [{"osuId":"3","name":"Admin","pairingPending":1,"pairingRanked":0,"pairingGraved":1},
            {"osuId":"1","name":"Viewer","pairingPending":0,"pairingRanked":1,"pairingGraved":0}]
            """,
        )
        get("/v2/profile/99/pairing").assertJson("[]")
    }

    @Test
    fun `Should save the modes a user is interested in`() {
        call(As.VIEWER, "PUT", "/v2/profile/me/interests", """["mania","osu"]""").assertJson(
            """{"osuId":"1","username":"Viewer","gamemodes":[{"gamemode":"osu","role":"Mapper"}],"interests":["osu","mania"]}""",
        )
        assertEquals(listOf(Gamemode.osu, Gamemode.mania), users.findUser("1")!!.interests)
        // They don't give any permission
        assertEquals(403, call(As.VIEWER, "PATCH", "/v2/beatmap/1/note", "note", "text/plain").status)

        call(As.VIEWER, "PUT", "/v2/profile/me/interests", "[]").assertJson(
            """{"osuId":"1","username":"Viewer","gamemodes":[{"gamemode":"osu","role":"Mapper"}]}""",
        )
        assertEquals(400, call(As.VIEWER, "PUT", "/v2/profile/me/interests", """["other"]""").status)
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
    fun `Should sync pending beatmaps in the background`() {
        saveEveryStatus()

        assertSynced(2, sync(As.ADMIN, "beatmaps/pending?status=Qualified,Reset"))
        jobs.finish()
        assertEquals(setOf("1", "4"), synced())
        get("/v2/admin/sync/waiting", As.ADMIN).assertJson("0")
        assertEquals(400, sync(As.ADMIN, "beatmaps/pending?status=Other").status)
    }

    // Live updates

    private fun waitForChanges(since: String): Pair<Long, JSONObject> {
        session(As.VIEWER)
        val start = System.currentTimeMillis()
        val response = http("GET", "/v2/planner/wait?since=$since", by = As.VIEWER)
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
