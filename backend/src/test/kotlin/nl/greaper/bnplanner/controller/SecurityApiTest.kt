package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.DefaultExceptionHandler
import nl.greaper.bnplanner.auth.LoginExpiredException
import nl.greaper.bnplanner.auth.OsuTokenVerifierTest.Companion.createToken
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.FakeOsu
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.mock.web.MockHttpServletRequest

class SecurityApiTest : ApiTest() {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "VIEWER | GET    | /v2/beatmap/1                                                | ",
            "VIEWER | GET    | /v2/beatmap/count?page=PENDING                               | ",
            "VIEWER | GET    | /v2/beatmap/find/table?page=PENDING&pageNumber=1&pageLimit=TEN | ",
            "VIEWER | GET    | /v2/planner                                                  | ",
            "VIEWER | GET    | /v2/planner/ranked                                           | ",
            "VIEWER | GET    | /v2/planner/wait                                             | ",
            "ADMIN  | GET    | /v2/admin/sync/waiting                                       | ",
            "ADMIN  | POST   | /v2/admin/sync/beatmaps/pending?background=true              | ",
            "VIEWER | GET    | /v2/profile/1/pairing                                        | ",
            "EDITOR | POST   | /v2/beatmap/add              | {\"osuId\":\"1\",\"gamemodes\":[]}",
            "EDITOR | PATCH  | /v2/beatmap/1/osu/nominator?old=0&new=1                      | ",
            "EDITOR | PATCH  | /v2/beatmap/1/note                                           | note",
            "EDITOR | DELETE | /v2/beatmap/1/delete                                         | ",
            "EDITOR | POST   | /v2/admin/sync/beatmaps/single?id=1                          | ",
            "ADMIN  | PATCH  | /v2/beatmap/1/status?new=Pending                             | ",
            "ADMIN  | POST   | /v2/admin/sync/users/ids                                     | []",
            "ADMIN  | POST   | /v2/admin/sync/beatmaps/pending                              | ",
        ],
    )
    fun `Should only allow the required role`(
        required: As,
        method: String,
        uri: String,
        body: String?,
    ) {
        val allowed =
            when (required) {
                As.VIEWER -> setOf(As.VIEWER, As.EDITOR, As.ADMIN, As.DEVELOPER)
                As.EDITOR -> setOf(As.EDITOR, As.ADMIN, As.DEVELOPER)
                As.ADMIN -> setOf(As.ADMIN, As.DEVELOPER)
                else -> setOf(As.DEVELOPER)
            }
        val contentType = if (uri.endsWith("note")) "text/plain" else body?.let { "application/json" }

        As.entries.forEach {
            clear()
            val response = call(it, method, uri, body, contentType)
            when (it) {
                As.NOBODY -> {
                    assertEquals(401, response.status, "$it $uri")
                    assertEquals("Full authentication is required to access this resource", response.errorMessage)
                }

                // Beatmap 1 is not on the planner, which is all right as long as it is allowed
                in allowed -> {
                    assertTrue(response.status in setOf(200, 404), "$it $uri ${response.status} ${response.contentAsString}")
                }

                else -> {
                    assertEquals(403, response.status, "$it $uri")
                    assertEquals("You are not allowed to do this", JSONObject(response.contentAsString).getString("detail"))
                    assertEquals(emptyList<Any>(), discord.sent)
                }
            }
        }
    }

    private fun search(authorization: String?) =
        call(As.NOBODY, "GET", "/v2/profile/1/pairing", headers = authorization?.let { mapOf(AUTHORIZATION to it) } ?: emptyMap()).status

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "Bearer",
            "Bearer ",
            "Bearer  token",
            "Bearer unreadable",
            "Basic abc",
            "Aiess x",
        ],
    )
    fun `Should refuse an invalid authorization`(authorization: String) {
        FakeOsu.respond("GET /api/v2/me", """{"id":1,"username":"Viewer"}""")

        assertEquals(401, search(authorization))
        assertEquals(401, call(As.NOBODY, "POST", "/v2/beatmap/add", headers = mapOf(AUTHORIZATION to authorization)).status)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should accept a verified token once and remember it`() {
        val token = createToken(subject = "1", expires = 4102444810)
        FakeOsu.respond("GET /api/v2/me", """{"id":1,"username":"Viewer"}""")

        assertEquals(200, search("Bearer $token"))
        assertEquals(200, search("Bearer $token"))
        assertEquals(200, search("Bearer $token and more"))
    }

    @Test
    fun `Should refuse a token that osu does not accept or is of another user`() {
        FakeOsu.respond("GET /api/v2/me", "{}", 401)
        assertEquals(401, search("Bearer ${createToken(subject = "1", expires = 4102444811)}"))

        FakeOsu.respond("GET /api/v2/me", """{"id":2,"username":"Editor"}""")
        assertEquals(401, search("Bearer ${createToken(subject = "1", expires = 4102444812)}"))
    }

    @Test
    fun `Should refuse a verified user the planner does not know`() {
        FakeOsu.respond("GET /api/v2/me", """{"id":555,"username":"Unknown"}""")

        assertEquals(401, search("Bearer ${createToken(subject = "555")}"))
    }

    @Test
    fun `Should be unavailable when osu can not verify a token`() {
        FakeOsu.respond("GET /api/v2/me", "{}", 500)

        assertEquals(503, search("Bearer ${createToken(subject = "1", expires = 4102444813)}"))
    }

    // CORS

    private fun preflight(
        origin: String,
        method: String = "PATCH",
        headers: String = "authorization,content-type",
    ) = call(
        As.NOBODY,
        "OPTIONS",
        "/v2/beatmap/1/status",
        headers =
            mapOf("Origin" to origin, "Access-Control-Request-Method" to method, "Access-Control-Request-Headers" to headers),
    )

    @ParameterizedTest
    @ValueSource(strings = ["http://localhost:3000", "http://localhost:8080"])
    fun `Should allow the configured origins`(origin: String) {
        val response = preflight(origin)

        assertEquals(200, response.status)
        assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("GET,POST,PUT,OPTIONS,DELETE,PATCH", response.getHeader("Access-Control-Allow-Methods"))
        assertEquals("authorization, content-type", response.getHeader("Access-Control-Allow-Headers"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        assertNull(response.getHeader("Access-Control-Max-Age"))

        val actual = call(As.VIEWER, "GET", "/v2/profile/1/pairing", headers = mapOf("Origin" to origin))
        assertEquals(200, actual.status)
        assertEquals(origin, actual.getHeader("Access-Control-Allow-Origin"))
        // Also when not logged in, otherwise the frontend can't see the status
        val refused = call(As.NOBODY, "GET", "/v2/profile/1/pairing", headers = mapOf("Origin" to origin))
        assertEquals(401, refused.status)
        assertEquals(origin, refused.getHeader("Access-Control-Allow-Origin"))
    }

    @Test
    fun `Should refuse other origins, methods and headers`() {
        listOf(
            preflight("http://localhost:3001"),
            preflight("http://localhost:3000", method = "TRACE"),
            preflight("http://localhost:3000", headers = "x-other"),
            call(As.VIEWER, "GET", "/v2/profile/1/pairing", headers = mapOf("Origin" to "http://localhost:3001")),
        ).forEach {
            assertEquals(403, it.status)
            assertEquals("Invalid CORS request", it.contentAsString)
            assertNull(it.getHeader("Access-Control-Allow-Origin"))
        }
    }

    // Errors

    @Test
    fun `Should report an unexpected error to the developer`() {
        val response = context.getBean(DefaultExceptionHandler::class.java).exception(IllegalStateException("made up"))
        assertEquals(500, response.statusCode.value())
        assertEquals("Unable to handle request", response.body)
        val sent = discord.single()
        assertTrue(sent.description.startsWith("**ERROR:**\n```\n${IllegalStateException::class.java.name}: made up"))
        assertTrue(sent.description.endsWith("\n```"))
        assertTrue(sent.description.length <= "**ERROR:**\n```\n\n```".length + 1600)
        assertEquals(
            listOf(EmbedColor.RED, EmbedThumbnail(""), EmbedFooter(""), true, emptyList<Any>()),
            listOf(sent.color, sent.thumbnail, sent.footer, sent.confidential, sent.gamemodes),
        )
    }

    @Test
    fun `Should ask to log in again without a session`() {
        val response = context.getBean(DefaultExceptionHandler::class.java).loginExpired(LoginExpiredException(), MockHttpServletRequest())

        assertEquals(401, response.statusCode.value())
    }

    // Using an actual server

    @Test
    fun `Should answer through an actual server`() {
        FakeOsu.respond("GET /api/v2/me", """{"id":1,"username":"Viewer"}""")
        val response = http("GET", "/v2/profile/2/pairing", mapOf(AUTHORIZATION to As.VIEWER.header!!))

        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").get())
        assertEquals("[]", response.body())
        assertEquals("no-cache, no-store, max-age=0, must-revalidate", response.headers().firstValue("Cache-Control").get())
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty)
    }

    @Test
    fun `Should refuse through an actual server`() {
        val response = http("GET", "/v2/profile/1/pairing")
        assertEquals(401, response.statusCode())
        assertEquals("", response.body())

        FakeOsu.respond("GET /api/v2/me", "{}", 503)
        val token = createToken(subject = "1", expires = 4102444814)
        assertEquals(503, http("GET", "/v2/profile/1/pairing", mapOf(AUTHORIZATION to "Bearer $token")).statusCode())
    }

    @Test
    fun `Should tell that the application is healthy without logging in`() {
        val response = http("GET", "/actuator/health")

        assertEquals(200, response.statusCode())
        assertEquals("""{"status":"UP"}""", response.body())
        assertEquals(401, http("GET", "/actuator/env").statusCode())
        assertEquals(401, http("GET", "/v2/auth/other").statusCode())
    }
}
