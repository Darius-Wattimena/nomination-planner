package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.assertRecent
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

class BeatmapApiTest : ApiTest() {
    private fun json(
        id: String,
        status: String = "Pending",
        extra: String = "",
        gamemodes: String =
            """[{"gamemode":"osu","nominators":[
            {"nominator":{"osuId":"0","username":"None","gamemodes":[]},"hasNominated":false},
            {"nominator":{"osuId":"0","username":"None","gamemodes":[]},"hasNominated":false}],"isReady":false}]""",
    ) = """
        {"osuId":"$id","artist":"Artist $id","title":"Title $id","note":"",
        "mapper":{"osuId":"1","username":"Viewer","gamemodes":[{"gamemode":"osu","role":"Mapper"}]},
        "status":"$status","gamemodes":$gamemodes,
        "dateAdded":"2024-01-01T10:00:00Z","dateUpdated":"2024-02-02T11:12:13.456Z"$extra}
        """

    private fun save(
        id: String,
        status: BeatmapStatus = BeatmapStatus.Pending,
        title: String = "Title $id",
    ) = beatmaps.insertOne(beatmap(id, status = status, mapperId = "1", title = title))

    private fun ids(response: MockHttpServletResponse): List<String> {
        assertEquals(200, response.status, response.contentAsString)
        val found = JSONArray(response.contentAsString)
        return (0 until found.length()).map { found.getJSONObject(it).getString("osuId") }
    }

    // Find

    @Test
    fun `Should find a beatmap`() {
        save("1")

        get("/v2/beatmap/1").assertJson(json("1"))
    }

    @Test
    fun `Should find a ranked beatmap with nominators`() {
        beatmaps.insertOne(
            beatmap(
                "1",
                mode(Gamemode.mania, "2", "3", firstNominated = true, isReady = true),
                mapperId = "1",
                note = "Notes: \"é\"\nline",
                status = BeatmapStatus.Ranked,
                ranked = UPDATED,
            ),
        )

        get("/v2/beatmap/1").assertJson(
            json(
                "1",
                "Ranked",
                ""","dateRanked":"2024-02-02T11:12:13.456Z"""",
                """[{"gamemode":"mania","nominators":[
                {"nominator":{"osuId":"2","username":"Editor","gamemodes":[{"gamemode":"osu","role":"Nominator"}]},"hasNominated":true},
                {"nominator":{"osuId":"3","username":"Admin","gamemodes":[{"gamemode":"mania","role":"NominationAssessment"}]},
                "hasNominated":false}],"isReady":true}]""",
            ).replace("\"note\":\"\"", """"note":"Notes: \"é\"\nline""""),
        )
    }

    @Test
    fun `Should write whole seconds and restricted users`() {
        users.saveUser(
            User("4", "RESTRICTED", emptyList(), restricted = true),
        )
        beatmaps.insertOne(beatmap("1", mapperId = "4", updated = Instant.parse("2024-02-02T11:12:00Z")))

        val found = JSONObject(get("/v2/beatmap/1").contentAsString)
        assertEquals("2024-02-02T11:12:00Z", found.getString("dateUpdated"))
        assertTrue(found.getJSONObject("mapper").getBoolean("restricted"))
    }

    @Test
    fun `Should not find a beatmap that is not on the planner`() {
        assertProblem(get("/v2/beatmap/1"), 404, "Not Found", "Beatmap 1 is not on the planner")
    }

    @Test
    fun `Should find beatmaps`() {
        save("1")
        save("2", BeatmapStatus.Ranked)
        save("3", BeatmapStatus.Qualified)

        get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=FIFTY").assertJson("[${json("3", "Qualified")},${json("1")}]")
        get("/v2/beatmap/find/table?page=GRAVEYARD&pageNumber=1&pageLimit=FIFTY").assertJson("[]")
    }

    @Test
    fun `Should find a table of beatmaps`() {
        beatmaps.insertMany((1..25).map { beatmap("$it", mapperId = "1", updated = UPDATED.minusSeconds(it.toLong())) })

        assertEquals((1..10).map { "$it" }, ids(get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=TEN")))
        assertEquals((21..25).map { "$it" }, ids(get("/v2/beatmap/find/table?page=PENDING&pageNumber=2&pageLimit=TWENTY")))
        assertEquals((1..25).map { "$it" }, ids(get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=FIFTY")))
    }

    @Test
    fun `Should count beatmaps`() {
        save("1")
        save("2", BeatmapStatus.Ranked)
        save("3", BeatmapStatus.Qualified)

        get("/v2/beatmap/count?page=PENDING").assertJson("2")
        get("/v2/beatmap/count?page=PENDING&status=Pending").assertJson("1")
        get("/v2/beatmap/count?page=GRAVEYARD").assertJson("0")
    }

    private fun findEverywhere(filter: String): Set<String> {
        val found = ids(get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=FIFTY&$filter")).toSet()
        get("/v2/beatmap/count?page=PENDING&$filter").assertJson("${found.size}")
        return found
    }

    @Test
    fun `Should filter beatmaps`() {
        beatmaps.insertOne(beatmap("1", mode(Gamemode.osu, "5", "0"), mapperId = "1", artist = "Camellia", title = "Ghost & more"))
        beatmaps.insertOne(
            beatmap("2", mode(Gamemode.taiko, "5", "6"), mapperId = "1", mapper = "Camellia", status = BeatmapStatus.Nominated),
        )
        beatmaps.insertOne(beatmap("3", mode(Gamemode.mania, "7", "0"), mapperId = "1", title = "どこ", status = BeatmapStatus.Qualified))

        assertEquals(setOf("1", "2"), findEverywhere("search=camellia"))
        assertEquals(setOf("1"), findEverywhere("artist=camellia"))
        assertEquals(setOf("1"), findEverywhere("title=ghost%20%26%20more"))
        assertEquals(setOf("3"), findEverywhere("title=%E3%81%93"))
        assertEquals(setOf("2"), findEverywhere("mapper=CAMELLIA"))
        assertEquals(setOf("2"), findEverywhere("status=Nominated"))
        assertEquals(setOf("2", "3"), findEverywhere("status=Nominated,Qualified"))
        assertEquals(setOf("2", "3"), findEverywhere("status=Nominated&status=Qualified"))
        assertEquals(setOf("1", "2"), findEverywhere("nominators=5"))
        assertEquals(setOf("2", "3"), findEverywhere("nominators=6,7"))
        assertEquals(setOf("2", "3"), findEverywhere("nominators=6&nominators=7"))
        assertEquals(setOf("1"), findEverywhere("gamemodes=osu"))
        assertEquals(setOf("1", "3"), findEverywhere("gamemodes=osu,mania"))
        assertEquals(setOf("1"), findEverywhere("missingNominator=osu"))
        assertEquals(setOf("1", "3"), findEverywhere("missingNominator=osu&missingNominator=mania"))
        assertEquals(setOf("1"), findEverywhere("search=ghost&status=Pending&nominators=5&gamemodes=osu&missingNominator=osu"))
        // Empty filters are no filters
        assertEquals(
            setOf("1", "2", "3"),
            findEverywhere("search=&artist=&title=&mapper=&status=&nominators=&gamemodes=&missingNominator="),
        )
    }

    @Test
    fun `Should only read enums in their exact case`() {
        assertEquals(400, get("/v2/beatmap/count?page=pending").status)
        assertEquals(400, get("/v2/beatmap/count?page=PENDING&status=PENDING").status)
        assertEquals(400, get("/v2/beatmap/count?page=PENDING&gamemodes=OSU").status)
        assertEquals(400, get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=ten").status)
    }

    // Add

    private fun add(body: String) = call(As.EDITOR, "POST", "/v2/beatmap/add", body)

    @Test
    fun `Should add a beatmap`() {
        FakeOsu.beatmap("1", userId = "1")
        val response = add("""{"osuId":"1","gamemodes":["osu"]}""")

        val added = JSONObject(response.contentAsString)
        assertRecent(Instant.parse(added.getString("dateAdded")))
        assertEquals(added.getString("dateAdded"), added.getString("dateUpdated"))
        response.assertJson(
            json("1")
                .replace("2024-01-01T10:00:00Z", added.getString("dateAdded"))
                .replace("2024-02-02T11:12:13.456Z", added.getString("dateAdded")),
        )
        assertEquals("Bearer ${As.EDITOR.accessToken}", FakeOsu.requests.single().header("Authorization"))
        assertEquals("Editor", discord.single().editor!!.username)
        assertEquals(listOf("1"), beatmaps.list().map { it.osuId })
    }

    @Test
    fun `Should accept a numeric id and unknown details when adding`() {
        FakeOsu.beatmap("1", userId = "1")

        assertEquals(200, add("""{"osuId":1,"gamemodes":[],"unknown":true}""").status)
        assertNotNull(beatmaps.findById("1"))
    }

    @Test
    fun `Should wait before finding an unknown mapper`() {
        FakeOsu.beatmap("1", userId = "77")
        FakeOsu.user("77", "New")

        assertEquals(200, add("""{"osuId":"1","gamemodes":["osu"]}""").status)
        assertEquals(listOf(1_000L), sleeper.sleeps)
    }

    @Test
    fun `Should not add a beatmap osu does not know`() {
        assertProblem(add("""{"osuId":"1","gamemodes":["osu"]}"""), 404, "Not Found", "osu! doesn't know beatmap 1")
    }

    // Nominators

    private fun patch(
        uri: String,
        by: As = As.EDITOR,
        body: String? = null,
        contentType: String? = null,
    ) = call(by, "PATCH", "/v2/beatmap/$uri", body, contentType ?: "application/json".takeIf { body != null })

    private fun nominators(response: MockHttpServletResponse): List<String> {
        assertEquals(200, response.status, response.contentAsString)
        val gamemodes = JSONObject(response.contentAsString).getJSONArray("gamemodes")
        return (0 until gamemodes.length()).map { index ->
            val gamemode = gamemodes.getJSONObject(index)
            val nominators = gamemode.getJSONArray("nominators")
            val ids = (0 until nominators.length()).map { nominators.getJSONObject(it).getJSONObject("nominator").getString("osuId") }
            "${gamemode.getString("gamemode")} $ids"
        }
    }

    @Test
    fun `Should update a single nominator`() {
        save("1")

        assertEquals(listOf("osu [2, 0]"), nominators(patch("1/osu/nominator?old=0&new=2")))
        assertEquals(listOf("osu [2, 3]"), nominators(patch("1/osu/nominator?old=0&new=3")))
        assertEquals(listOf("osu [2, 3]", "mania [3, 0]"), nominators(patch("1/mania/nominator?old=0&new=3")))
        assertEquals(listOf("osu [2, 3]"), nominators(patch("1/mania/nominator?old=3&new=0")))
        assertProblem(patch("2/osu/nominator?old=0&new=2"), 404, "Not Found", "Beatmap 2 is not on the planner")
        // Already replaced by someone else
        assertProblem(patch("1/osu/nominator?old=0&new=1"), 409, "Conflict", "Someone else changed the nominators first")
    }

    // Status

    @Test
    fun `Should update the status`() {
        save("1")

        patch("1/status?new=Graved", As.ADMIN).assertEmpty()
        assertEquals(BeatmapStatus.Graved, beatmaps.findById("1")!!.status)
        assertProblem(patch("2/status?new=Graved", As.ADMIN), 404, "Not Found", "Beatmap 2 is not on the planner")
    }

    // Note

    @Test
    fun `Should update the note`() {
        save("1")

        patch("1/note", body = "New \"note\" é", contentType = "text/plain;charset=UTF-8").assertEmpty()
        assertEquals("New \"note\" é", beatmaps.findById("1")!!.note)
        assertProblem(patch("2/note", body = "note", contentType = "text/plain"), 404, "Not Found", "Beatmap 2 is not on the planner")
    }

    @Test
    fun `Should clear the note`() {
        beatmaps.insertOne(beatmap("1", mapperId = "1", note = "old"))

        patch("1/note", body = "", contentType = "text/plain").assertEmpty()
        assertEquals("", beatmaps.findById("1")!!.note)
    }

    @Test
    fun `Should clear the note through an actual server`() {
        beatmaps.insertOne(beatmap("1", mapperId = "1", note = "old"))

        assertEquals("", http("PATCH", "/v2/beatmap/1/note", mapOf("Content-Type" to "text/plain"), "", As.EDITOR).body())
        assertEquals("", beatmaps.findById("1")!!.note)
    }

    @Test
    fun `Should only accept a note as plain text`() {
        val response = patch("1/note", body = "\"note\"", contentType = "application/json")

        assertEquals(415, response.status)
        assertEquals("text/plain", response.getHeader("Accept-Patch") ?: response.getHeader("Accept"))
    }

    // Delete

    @Test
    fun `Should delete a beatmap`() {
        save("1")

        call(As.EDITOR, "DELETE", "/v2/beatmap/1/delete").assertEmpty()
        assertNull(beatmaps.findById("1"))
        call(As.EDITOR, "DELETE", "/v2/beatmap/1/delete").assertEmpty()
    }

    // Invalid requests

    private fun assertProblem(
        response: MockHttpServletResponse,
        status: Int,
        title: String,
        detail: String,
    ) {
        assertEquals(status, response.status)
        assertEquals("application/problem+json", response.contentType)
        val problem = JSONObject(response.contentAsString)
        assertEquals(status, problem.getInt("status"))
        assertEquals(title, problem.getString("title"))
        assertEquals(detail, problem.getString("detail"))
        // Not something that went wrong in the planner
        assertTrue(discord.sent.none { it.description.startsWith("**ERROR:**") })
    }

    private fun assertBadRequest(
        response: MockHttpServletResponse,
        detail: String,
    ) = assertProblem(response, 400, "Bad Request", detail)

    @Test
    fun `Should refuse missing parameters`() {
        assertBadRequest(get("/v2/beatmap/find/table?pageNumber=1&pageLimit=TEN"), "Required parameter 'page' is not present.")
        assertBadRequest(
            get("/v2/beatmap/find/table?page=PENDING&pageLimit=TEN"),
            "Required parameter 'pageNumber' is not present.",
        )
        assertBadRequest(
            get("/v2/beatmap/find/table?page=PENDING&pageNumber=1"),
            "Required parameter 'pageLimit' is not present.",
        )
        assertBadRequest(get("/v2/beatmap/count"), "Required parameter 'page' is not present.")
        assertBadRequest(patch("1/osu/nominator?old=0"), "Required parameter 'new' is not present.")
        assertBadRequest(patch("1/status", As.ADMIN), "Required parameter 'new' is not present.")
    }

    @Test
    fun `Should refuse invalid parameters`() {
        assertBadRequest(
            get("/v2/beatmap/find/table?page=OTHER&pageNumber=1&pageLimit=TEN"),
            "Failed to convert 'page' with value: 'OTHER'",
        )
        assertBadRequest(
            get("/v2/beatmap/find/table?page=PENDING&pageNumber=a&pageLimit=TEN"),
            "Failed to convert 'pageNumber' with value: 'a'",
        )
        assertBadRequest(get("/v2/beatmap/count?page=PENDING&status=Other"), "Failed to convert 'status' with value: 'Other'")
        assertBadRequest(
            get("/v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=HUNDRED"),
            "Failed to convert 'pageLimit' with value: 'HUNDRED'",
        )
        assertBadRequest(patch("1/catch/nominator?old=0&new=1"), "Failed to convert 'mode' with value: 'catch'")
    }

    @Test
    fun `Should refuse unreadable bodies`() {
        listOf("", "{", "{}", "[]", """{"osuId":"1"}""", """{"osuId":"1","gamemodes":["catch"]}""").forEach {
            assertBadRequest(add(it), "Failed to read request")
        }
    }

    @Test
    fun `Should refuse unknown paths, methods and content types`() {
        assertProblem(get("/v2/unknown"), 404, "Not Found", "No static resource v2/unknown.")
        assertProblem(
            call(As.EDITOR, "POST", "/v2/beatmap/1"),
            405,
            "Method Not Allowed",
            "Method 'POST' is not supported.",
        )
        assertProblem(
            call(As.EDITOR, "POST", "/v2/beatmap/add", "x", "text/plain;charset=UTF-8"),
            415,
            "Unsupported Media Type",
            "Content-Type 'text/plain;charset=UTF-8' is not supported.",
        )
    }
}
