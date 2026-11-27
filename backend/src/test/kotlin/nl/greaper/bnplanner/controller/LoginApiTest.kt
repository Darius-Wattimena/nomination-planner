package nl.greaper.bnplanner.controller

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import jakarta.servlet.http.Cookie
import nl.greaper.bnplanner.auth.LoginFlow
import nl.greaper.bnplanner.config.SecurityConfig
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.TestMongo
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.util.Base64
import java.util.Date

/** Logging in with osu!, and staying logged in through the session */
class LoginApiTest : ApiTest() {
    private fun startLogin(returnTo: String? = null): MockHttpServletResponse =
        mvc
            .perform(MockMvcRequestBuilders.get("/oauth2/authorization/osu").apply { returnTo?.let { param("returnTo", it) } })
            .andReturn()
            .response

    private fun finishLogin(
        start: MockHttpServletResponse,
        vararg params: Pair<String, String>,
    ): MockHttpServletResponse {
        val state =
            UriComponentsBuilder
                .fromUriString(start.redirectedUrl!!)
                .build()
                .queryParams
                .getFirst("state")!!
        val request =
            MockMvcRequestBuilders
                .get("/login/oauth2/code/osu")
                .param("state", URLDecoder.decode(state, Charsets.UTF_8))
                .cookie(start.getCookie(SESSION_COOKIE)!!)
        params.forEach { (name, value) -> request.param(name, value) }
        return mvc.perform(request).andReturn().response
    }

    private fun osuKnows(
        id: String,
        username: String,
        groups: String? = """[{"id":28,"playmodes":["osu"]}]""",
    ) {
        FakeOsu.token("made-up-access", "made-up-refresh")
        FakeOsu.respond("GET /api/v2/me", """{"id":$id,"username":"$username","country_code":"NL"}""")
        if (groups != null) FakeOsu.user(id, username, groups) else FakeOsu.respond("GET /api/v2/users/$id?key=id", "{}", 503)
    }

    private fun sessionOf(response: MockHttpServletResponse) =
        mvc
            .perform(MockMvcRequestBuilders.get("/v2/session").cookie(response.getCookie(SESSION_COOKIE)!!))
            .andReturn()
            .response

    private fun sessionOf(cookie: Cookie) =
        mvc
            .perform(MockMvcRequestBuilders.get("/v2/session").cookie(cookie))
            .andReturn()
            .response

    private fun storedLogin(osuId: String) = TestMongo.documents("osuLogins").single { it["_id"] == osuId }

    // Logging in

    @Test
    fun `Should send the browser to osu to log in`() {
        val start = startLogin()

        assertEquals(302, start.status)
        val redirect = UriComponentsBuilder.fromUriString(start.redirectedUrl!!).build()
        assertEquals("${FakeOsu.url}/oauth/authorize", "${redirect.scheme}://${redirect.host}:${redirect.port}${redirect.path}")
        assertEquals("14229", redirect.queryParams.getFirst("client_id"))
        assertEquals("code", redirect.queryParams.getFirst("response_type"))
        assertEquals("identify%20public", redirect.queryParams.getFirst("scope"))
        assertEquals(
            "http://localhost/login/oauth2/code/osu",
            URLDecoder.decode(redirect.queryParams.getFirst("redirect_uri"), Charsets.UTF_8),
        )
        assertNotNull(start.getCookie(SESSION_COOKIE))
        assertTrue(start.getCookie(SESSION_COOKIE)!!.isHttpOnly)
        assertEquals("Lax", start.getCookie(SESSION_COOKIE)!!.getAttribute("SameSite"))
    }

    @Test
    fun `Should log in with osu and remember them`() {
        osuKnows("5", "New BN")
        val response = finishLogin(startLogin("/beatmaps?page=2"), "code" to "made-up-code")

        assertEquals(302, response.status)
        assertEquals("http://localhost:3000/beatmaps?page=2", response.redirectedUrl)
        assertEquals(listOf("POST /oauth/token", "GET /api/v2/me", "GET /api/v2/users/5?key=id"), FakeOsu.calls())
        assertTrue(FakeOsu.requests[0].body.contains("code=made-up-code"), FakeOsu.requests[0].body)
        assertTrue(FakeOsu.requests[0].body.contains("client_secret=made-up-secret"), FakeOsu.requests[0].body)
        assertEquals("Bearer made-up-access", FakeOsu.requests[1].header("Authorization"))
        assertEquals(User("5", "New BN", listOf(UserGamemode(Gamemode.osu, Role.Nominator))), users.findUser("5"))

        val sent = discord.single()
        assertEquals("📗 **LOGIN:** New BN logged in", sent.description)
        assertEquals(EmbedColor.GREEN, sent.color)

        val session = JSONObject(sessionOf(response).contentAsString)
        assertEquals("5", session.getJSONObject("user").getString("osuId"))
        assertEquals("Nominator", session.getJSONObject("permission").getString("osuRole"))
        assertEquals("""["VIEWER","EDITOR"]""", session.getJSONObject("permission").getJSONArray("roles").toString())
        assertTrue(session.getString("csrfToken").isNotBlank())

        val stored = storedLogin("5")
        assertEquals("made-up-access", stored["accessToken"])
        assertEquals("made-up-refresh", stored["refreshToken"])
        // osu! doesn't tell which scopes it gave
        assertEquals(emptyList<String>(), stored["scopes"])
        assertTrue(TestMongo.documents("sessions").isNotEmpty())
    }

    /** How long the session of the browser would still be kept */
    private fun sessionSecondsLeft(response: MockHttpServletResponse): Long {
        val id = String(Base64.getDecoder().decode(response.getCookie(SESSION_COOKIE)!!.value))
        val expireAt = TestMongo.documents("sessions").single { it["_id"] == id }["expireAt"] as Date
        return (expireAt.time - System.currentTimeMillis()) / 1000
    }

    @Test
    fun `Should only keep the session of someone logging in for a short while until they logged in`() {
        osuKnows("5", "New BN")
        val start = startLogin()
        assertTrue(sessionSecondsLeft(start) in 500..600, "${sessionSecondsLeft(start)}")

        val loggedIn = finishLogin(start, "code" to "made-up-code")
        assertEquals(302, loggedIn.status)
        assertTrue(sessionSecondsLeft(loggedIn) > 29L * 24 * 60 * 60, "${sessionSecondsLeft(loggedIn)}")
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "/planner          | /planner",
            "//elsewhere.com   | /",
            "https://elsewhere | /",
            "/\\elsewhere.com  | /",
            "''                | /",
        ],
        delimiter = '|',
    )
    fun `Should only go back to a page of the website`(
        returnTo: String,
        page: String,
    ) {
        osuKnows("1", "Viewer", groups = null)

        assertEquals("http://localhost:3000$page", finishLogin(startLogin(returnTo), "code" to "code").redirectedUrl)
    }

    @Test
    fun `Should go back to the start without a page`() {
        osuKnows("1", "Viewer", groups = null)

        assertEquals("http://localhost:3000/", finishLogin(startLogin(), "code" to "code").redirectedUrl)
    }

    @Test
    fun `Should go back to the start when the page to go back to is gone`() {
        val response = MockHttpServletResponse()
        context
            .getBean(
                LoginFlow::class.java,
            ).onAuthenticationSuccess(MockHttpServletRequest(), response, TestingAuthenticationToken("1", null))

        assertEquals("http://localhost:3000/", response.redirectedUrl)
    }

    @Test
    fun `Should log in a known user when osu can not tell more about them`() {
        osuKnows("2", "Editor", groups = null)
        val response = finishLogin(startLogin(), "code" to "code")

        assertEquals("http://localhost:3000/", response.redirectedUrl)
        assertEquals("Editor", JSONObject(sessionOf(response).contentAsString).getJSONObject("user").getString("username"))
    }

    @Test
    fun `Should not log in someone the planner does not know when osu can not tell more about them`() {
        osuKnows("555", "Unknown", groups = null)
        val start = startLogin()
        val response = finishLogin(start, "code" to "code")

        assertEquals("http://localhost:3000/login?failed", response.redirectedUrl)
        assertEquals(204, sessionOf(start.getCookie(SESSION_COOKIE)!!).status)
        assertTrue(discord.single().description.startsWith("📕 **ERROR:** Unexpected error occurred while logging in"))
    }

    @Test
    fun `Should go back to the website when osu refuses the code`() {
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_grant"}""", 400)
        val response = finishLogin(startLogin(), "code" to "code")

        assertEquals("http://localhost:3000/login?failed", response.redirectedUrl)
        assertEquals(EmbedColor.RED, discord.single().color)
    }

    @Test
    fun `Should not report someone choosing not to log in`() {
        val response = finishLogin(startLogin(), "error" to "access_denied")

        assertEquals("http://localhost:3000/login?failed", response.redirectedUrl)
        assertEquals(emptyList<Any>(), discord.sent)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should not log in without starting from the planner`() {
        val forged =
            mvc
                .perform(MockMvcRequestBuilders.get("/login/oauth2/code/osu").param("code", "c").param("state", "s"))
                .andReturn()
                .response

        assertEquals("http://localhost:3000/login?failed", forged.redirectedUrl)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    // The session

    @Test
    fun `Should tell nobody is logged in`() {
        val response = call(As.NOBODY, "GET", "/v2/session")

        assertEquals(204, response.status)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `Should tell who is logged in`() {
        call(As.ADMIN, "GET", "/v2/session").assertJson(
            """
            {"user":{"osuId":"3","username":"Admin","gamemodes":[{"gamemode":"mania","role":"NominationAssessment"}]},
            "permission":{"osuRole":"NominationAssessment","roles":["ADMIN","VIEWER","EDITOR"]},
            "csrfToken":"${session(As.ADMIN).csrfToken}"}
            """,
        )
    }

    @Test
    fun `Should use the roles someone has right now`() {
        // Allowed, but not on the planner
        assertEquals(404, call(As.EDITOR, "POST", "/v2/beatmap/1/sync").status)

        users.addInMemoryUser(User("2", "Editor", listOf(UserGamemode(Gamemode.osu, Role.Mapper))))
        assertEquals(403, call(As.EDITOR, "POST", "/v2/beatmap/1/sync").status)
        assertEquals(
            "Mapper",
            JSONObject(call(As.EDITOR, "GET", "/v2/session").contentAsString).getJSONObject("permission").getString("osuRole"),
        )
    }

    @Test
    fun `Should treat someone the planner no longer knows as not logged in`() {
        session(As.EDITOR)
        users.forgetInMemoryUsers()

        assertEquals(204, call(As.EDITOR, "GET", "/v2/session").status)
        assertEquals(401, call(As.EDITOR, "GET", "/v2/planner").status)
    }

    @Test
    fun `Should only accept changes with the token of the session`() {
        val session = session(As.EDITOR)
        val request = { token: String? ->
            mvc
                .perform(
                    MockMvcRequestBuilders.post("/v2/beatmap/1/sync").cookie(session.cookie).apply {
                        token?.let { header(SecurityConfig.CSRF_HEADER, it) }
                    },
                ).andReturn()
                .response.status
        }

        assertEquals(403, request(null))
        assertEquals(403, request("made-up"))
        // Allowed, but not on the planner
        assertEquals(404, request(session.csrfToken))
        // Reading needs no token
        assertEquals(
            200,
            mvc
                .perform(MockMvcRequestBuilders.get("/v2/planner").cookie(session.cookie))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `Should log out`() {
        val session = session(As.EDITOR)
        val logout = { token: String? ->
            mvc
                .perform(
                    MockMvcRequestBuilders.post("/v2/logout").cookie(session.cookie).apply {
                        token?.let { header(SecurityConfig.CSRF_HEADER, it) }
                    },
                ).andReturn()
                .response
        }

        assertEquals(403, logout(null).status)
        assertEquals(200, call(As.EDITOR, "GET", "/v2/planner").status)

        assertEquals(204, logout(session.csrfToken).status)
        assertEquals(204, call(As.EDITOR, "GET", "/v2/session").status)
        assertEquals(401, call(As.EDITOR, "GET", "/v2/planner").status)
    }

    @Test
    fun `Should allow the website to send the cookie along`() {
        val response =
            call(
                As.NOBODY,
                "OPTIONS",
                "/v2/logout",
                headers =
                    mapOf(
                        "Origin" to "http://localhost:3000",
                        "Access-Control-Request-Method" to "POST",
                        "Access-Control-Request-Headers" to "x-csrf-token",
                    ),
            )

        assertEquals(200, response.status)
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        assertEquals("x-csrf-token", response.getHeader("Access-Control-Allow-Headers"))
    }

    // The osu! token

    private fun expireToken(osuId: String) {
        TestMongo.raw
            .getCollection("osuLogins")
            .updateOne(
                Filters.eq("_id", osuId),
                Updates.combine(
                    Updates.set("issuedAt", Date(System.currentTimeMillis() - 90_000_000)),
                    Updates.set("expiresAt", Date(System.currentTimeMillis() - 1000)),
                ),
            )
    }

    private fun addBeatmap() = call(As.EDITOR, "POST", "/v2/beatmap/add", """{"osuId":"1","gamemodes":["osu"]}""")

    @Test
    fun `Should ask osu on behalf of whoever is logged in`() {
        FakeOsu.beatmap("1", userId = "1")

        assertEquals(200, addBeatmap().status)
        assertEquals("Bearer access-2", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should renew the token of osu once it expired`() {
        session(As.EDITOR)
        expireToken("2")
        FakeOsu.token("renewed", "next-refresh")
        FakeOsu.beatmap("1", userId = "1")

        assertEquals(200, addBeatmap().status)
        assertEquals(listOf("POST /oauth/token", "GET /api/v2/beatmapsets/1"), FakeOsu.calls())
        assertTrue(FakeOsu.requests[0].body.contains("grant_type=refresh_token"), FakeOsu.requests[0].body)
        assertTrue(FakeOsu.requests[0].body.contains("refresh_token=refresh-2"), FakeOsu.requests[0].body)
        assertEquals("Bearer renewed", FakeOsu.requests[1].header("Authorization"))
        assertEquals("next-refresh", storedLogin("2")["refreshToken"])
    }

    @Test
    fun `Should log out when osu no longer accepts the login`() {
        session(As.EDITOR)
        expireToken("2")
        FakeOsu.respond("POST /oauth/token", """{"error":"invalid_grant"}""", 400)

        val response = addBeatmap()
        assertEquals(401, response.status)
        assertEquals("Please log in again", JSONObject(response.contentAsString).getString("detail"))
        assertEquals(204, call(As.EDITOR, "GET", "/v2/session").status)
        assertEquals(emptyList<Any>(), TestMongo.documents("osuLogins"))
    }

    @Test
    fun `Should log out when the token of osu is gone`() {
        session(As.EDITOR)
        TestMongo.raw.getCollection("osuLogins").deleteMany(Filters.empty())

        assertEquals(401, addBeatmap().status)
    }

    @Test
    fun `Should stay logged in when osu can not renew the token right now`() {
        session(As.EDITOR)
        expireToken("2")
        FakeOsu.respond("POST /oauth/token", "{}", 500)

        val response = addBeatmap()
        assertEquals(503, response.status)
        assertEquals("osu! can not be reached right now", JSONObject(response.contentAsString).getString("detail"))
        assertEquals(200, call(As.EDITOR, "GET", "/v2/session").status)
        assertNotEquals(null, storedLogin("2")["refreshToken"])
    }

    @Test
    fun `Should share the token of osu between the devices of someone`() {
        val phone = logIn(As.EDITOR)
        val computer = logIn(As.EDITOR)

        assertNotEquals(phone.cookie.value, computer.cookie.value)
        assertEquals(1, TestMongo.documents("osuLogins").size)
        assertEquals(200, call(As.EDITOR, "GET", "/v2/planner").status)
    }
}
