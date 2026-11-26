package nl.greaper.bnplanner.support

import jakarta.servlet.http.Cookie
import nl.greaper.bnplanner.Application
import nl.greaper.bnplanner.config.SecurityConfig
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.util.Sleeper
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The whole application including its security, using a real MongoDB and a fake osu!
 *
 * Nothing of the application itself is mocked, so this shows what the frontend gets.
 */
@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ApiTest.Config::class)
abstract class ApiTest {
    @TestConfiguration
    class Config {
        @Bean
        fun recordingDiscord() = RecordingDiscord()

        @Bean
        fun sleeper() = RecordingSleeper()
    }

    class RecordingSleeper : Sleeper {
        val sleeps = mutableListOf<Long>()

        override fun sleep(millis: Long) {
            sleeps += millis
        }
    }

    enum class As(
        val osuId: String?,
    ) {
        NOBODY(null),
        VIEWER("1"),
        EDITOR("2"),
        ADMIN("3"),
        DEVELOPER("2369776"),
        ;

        /** The osu! token the planner got for them when they logged in */
        val accessToken: String get() = "access-$osuId"
    }

    /** The cookie of a browser that logged in, with the token to send along with changes */
    data class Session(
        val cookie: Cookie,
        val csrfToken: String,
    )

    companion object {
        const val SESSION_COOKIE = "PLANNER_SESSION"

        private val users =
            mapOf(
                As.VIEWER to User("1", "Viewer", listOf(UserGamemode(Gamemode.osu, Role.Mapper))),
                As.EDITOR to User("2", "Editor", listOf(UserGamemode(Gamemode.osu, Role.Nominator))),
                As.ADMIN to User("3", "Admin", listOf(UserGamemode(Gamemode.mania, Role.NominationAssessment))),
                As.DEVELOPER to User("2369776", "Developer", emptyList()),
            )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("mongodb.host") { TestMongo.host }
            registry.add("mongodb.port") { TestMongo.port }
            registry.add("mongodb.db") { TestMongo.DATABASE }
            registry.add("osu.baseUrl") { FakeOsu.url }
            registry.add("osu.clientId") { "14229" }
            registry.add("osu.clientSecret") { "made-up-secret" }
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var users: UserDataSource

    @Autowired
    lateinit var beatmaps: BeatmapDataSource

    @Autowired
    lateinit var discord: RecordingDiscord

    @Autowired
    lateinit var sleeper: RecordingSleeper

    @LocalServerPort
    var serverPort: Int = 0

    private val sessions = mutableMapOf<As, Session>()

    @BeforeEach
    fun clear() {
        sessions.clear()
        TestMongo.clear()
        beatmaps.load()
        FakeOsu.reset()
        discord.sent.clear()
        sleeper.sleeps.clear()
        users.forgetInMemoryUsers()
        Companion.users.values.forEach { users.addInMemoryUser(it) }
    }

    /** Log in with osu! like a browser does, without leaving a trace in what osu! or Discord got */
    fun logIn(
        by: As,
        returnTo: String? = "/",
    ): Session {
        val known = FakeOsu.requests.size to discord.sent.size
        val start =
            mvc
                .perform(MockMvcRequestBuilders.get("/oauth2/authorization/osu").apply { returnTo?.let { param("returnTo", it) } })
                .andReturn()
                .response
        val state =
            UriComponentsBuilder
                .fromUriString(start.redirectedUrl!!)
                .build()
                .queryParams
                .getFirst("state")!!

        val callback =
            FakeOsu.temporarily(
                "POST /oauth/token" to FakeOsu.tokenBody(by.accessToken, "refresh-${by.osuId}"),
                "GET /api/v2/me" to """{"id":${by.osuId},"username":"Someone"}""",
                // What the planner knows of them stays as it is
                "GET /api/v2/users/${by.osuId}?key=id" to "unavailable",
            ) {
                mvc
                    .perform(
                        MockMvcRequestBuilders
                            .get("/login/oauth2/code/osu")
                            .param("code", "code")
                            .param("state", URLDecoder.decode(state, Charsets.UTF_8))
                            .cookie(start.getCookie(SESSION_COOKIE)!!),
                    ).andReturn()
                    .response
            }
        assertEquals(302, callback.status, callback.contentAsString)

        val cookie = callback.getCookie(SESSION_COOKIE)!!
        val session = mvc.perform(MockMvcRequestBuilders.get("/v2/session").cookie(cookie)).andReturn().response

        while (FakeOsu.requests.size > known.first) FakeOsu.requests.removeLast()
        while (discord.sent.size > known.second) discord.sent.removeLast()
        return Session(cookie, JSONObject(session.contentAsString).getString("csrfToken"))
    }

    fun session(by: As) = sessions.getOrPut(by) { logIn(by) }

    /** Perform a request, the uri has to be encoded already */
    fun call(
        by: As,
        method: String,
        uri: String,
        body: String? = null,
        contentType: String? = MediaType.APPLICATION_JSON_VALUE.takeIf { body != null },
        headers: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse {
        val request = request(HttpMethod.valueOf(method), URI.create(uri))
        if (by != As.NOBODY) {
            val session = session(by)
            request.cookie(session.cookie)
            request.header(SecurityConfig.CSRF_HEADER, session.csrfToken)
        }
        body?.let { request.content(it) }
        contentType?.let { request.contentType(it) }
        headers.forEach { (name, value) -> request.header(name, value) }

        return mvc.perform(request).andReturn().response
    }

    fun get(
        uri: String,
        by: As = As.VIEWER,
    ) = call(by, "GET", uri)

    /** Perform a request using an actual server, which reads for example forms different from MockMvc */
    fun http(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        by: As = As.NOBODY,
    ): HttpResponse<String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$serverPort$uri")).method(method, publisher)
        if (by != As.NOBODY) {
            val session = session(by)
            request.header("Cookie", "$SESSION_COOKIE=${session.cookie.value}")
            request.header(SecurityConfig.CSRF_HEADER, session.csrfToken)
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun MockHttpServletResponse.assertJson(
        expected: String,
        status: Int = 200,
    ) {
        assertEquals(status, this.status, contentAsString)
        assertEquals("application/json", contentType)
        val actual = getContentAsString(Charsets.UTF_8)
        if (actual.startsWith("{") || actual.startsWith("[")) {
            JSONAssert.assertEquals(expected, actual, JSONCompareMode.STRICT)
        } else {
            assertEquals(expected, actual)
        }
    }

    fun MockHttpServletResponse.assertEmpty(status: Int = 200) {
        assertEquals(status, this.status)
        assertEquals("", contentAsString)
    }

    /** What every exception that isn't expected turns into */
    fun MockHttpServletResponse.assertUnableToHandle() {
        assertEquals(500, status)
        assertEquals("Unable to handle request", contentAsString)
    }
}
