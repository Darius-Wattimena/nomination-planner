package nl.greaper.bnplanner.support

import nl.greaper.bnplanner.Application
import nl.greaper.bnplanner.auth.OsuTokenVerifierTest.Companion.createToken
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.util.Sleeper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The whole application including its security, using a real MongoDB and a fake osu!
 *
 * Nothing of the application itself is mocked, so this shows what the frontend and Aiess get.
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
        val header: String?,
    ) {
        NOBODY(null),
        VIEWER("Bearer ${createToken(subject = "1")}"),
        EDITOR("Bearer ${createToken(subject = "2")}"),
        ADMIN("Bearer ${createToken(subject = "3")}"),
        DEVELOPER("Bearer ${createToken(subject = "2369776")}"),
        BOT("Aiess $AIESS_TOKEN"),
    }

    companion object {
        const val AIESS_TOKEN = "made-up-aiess-token"
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
            registry.add("osu.redirectUri") { "http://localhost:3000/login" }
            registry.add("aiess.token") { AIESS_TOKEN }
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

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

    @BeforeEach
    fun clear() {
        TestMongo.clear()
        beatmaps.load()
        FakeOsu.reset()
        discord.sent.clear()
        sleeper.sleeps.clear()
        users.usersByOsuId.clear()
        Gamemode.entries.forEach { users.usersByGamemode[it] = emptySet() }
        Companion.users.values.forEach { users.addInMemoryUser(it) }
    }

    /** Perform a request, the uri has to be encoded already */
    fun call(
        by: As,
        method: String,
        uri: String,
        body: String? = null,
        contentType: String? = MediaType.APPLICATION_JSON_VALUE.takeIf { body != null },
        headers: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse {
        // Accept the token when it is verified
        Companion.users[by]?.let { FakeOsu.respond("GET /api/v2/me", """{"id":${it.osuId},"username":"${it.username}"}""") }

        val request = request(HttpMethod.valueOf(method), URI.create(uri))
        by.header?.let { request.header(AUTHORIZATION, it) }
        body?.let { request.content(it) }
        contentType?.let { request.contentType(it) }
        headers.forEach { (name, value) -> request.header(name, value) }

        val response = mvc.perform(request).andReturn().response
        FakeOsu.requests.removeIf { it.uri == "/api/v2/me" }
        return response
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
    ): HttpResponse<String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$serverPort$uri")).method(method, publisher)
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
        assertEquals(400, status)
        assertEquals("Unable to handle request", contentAsString)
    }
}
