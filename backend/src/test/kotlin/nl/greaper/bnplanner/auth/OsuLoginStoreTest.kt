package nl.greaper.bnplanner.auth

import nl.greaper.bnplanner.config.LoginConfig
import nl.greaper.bnplanner.config.LoginProperties
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import java.time.Instant
import java.util.Date

class OsuLoginStoreTest {
    private val registrations = LoginConfig().clientRegistrations(OsuProperties("14229", "secret", ""))
    private val store = OsuLoginStore(TestMongo.database, registrations)
    private val registration = registrations.findByRegistrationId("osu")!!

    private val issued = Instant.parse("2026-01-01T10:00:00Z")
    private val expires = Instant.parse("2026-01-02T10:00:00Z")
    private val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", issued, expires, setOf("public", "identify"))

    @BeforeEach
    fun setup() = TestMongo.clear()

    @Test
    fun `Should store the tokens of someone in the expected document shape`() {
        store.saveAuthorizedClient(
            OAuth2AuthorizedClient(registration, "5", token, OAuth2RefreshToken("refresh", null)),
            TestingAuthenticationToken("5", null),
        )

        assertEquals(
            listOf(
                Document("_id", "5")
                    .append("accessToken", "access")
                    .append("issuedAt", Date.from(issued))
                    .append("expiresAt", Date.from(expires))
                    .append("scopes", listOf("identify", "public"))
                    .append("refreshToken", "refresh"),
            ),
            TestMongo.documents("osuLogins"),
        )

        val loaded = store.loadAuthorizedClient<OAuth2AuthorizedClient>("osu", "5")!!
        assertEquals("5", loaded.principalName)
        assertEquals(registration, loaded.clientRegistration)
        assertEquals(
            listOf("access", issued, expires, setOf("identify", "public")),
            with(loaded.accessToken) {
                listOf(tokenValue, issuedAt, expiresAt, scopes)
            },
        )
        assertEquals("refresh", loaded.refreshToken!!.tokenValue)
    }

    @Test
    fun `Should replace the tokens of someone and remove them`() {
        store.saveAuthorizedClient(
            OAuth2AuthorizedClient(registration, "5", token, OAuth2RefreshToken("refresh", null)),
            TestingAuthenticationToken("5", null),
        )
        store.saveAuthorizedClient(OAuth2AuthorizedClient(registration, "5", token), TestingAuthenticationToken("5", null))

        assertNull(store.loadAuthorizedClient<OAuth2AuthorizedClient>("osu", "5")!!.refreshToken)
        assertEquals(1, TestMongo.documents("osuLogins").size)

        store.removeAuthorizedClient("osu", "5")
        assertNull(store.loadAuthorizedClient<OAuth2AuthorizedClient>("osu", "5"))
    }

    @Test
    fun `Should know nothing of another login`() {
        store.saveAuthorizedClient(OAuth2AuthorizedClient(registration, "5", token), TestingAuthenticationToken("5", null))

        assertNull(store.loadAuthorizedClient<OAuth2AuthorizedClient>("other", "5"))
        assertNull(store.loadAuthorizedClient<OAuth2AuthorizedClient>("osu", "6"))
    }

    @Test
    fun `Should use the configured callback of osu`() {
        val configured = LoginConfig().clientRegistrations(OsuProperties("14229", "secret", "https://backend/login/oauth2/code/osu"))

        assertEquals("https://backend/login/oauth2/code/osu", configured.findByRegistrationId("osu")!!.redirectUri)
        assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", registration.redirectUri)
    }

    @Test
    fun `Should remember the page when osu asks to log in again`() {
        val planner = Planner()
        val flow = LoginFlow(registrations, LoginProperties("http://localhost:3000"), planner.userService, planner.authService)
        val request = MockHttpServletRequest("GET", "/beatmaps").apply { addParameter("returnTo", "/beatmaps") }

        assertEquals("osu", flow.resolve(request, "osu")!!.attributes["registration_id"])
        assertEquals("/beatmaps", request.session!!.getAttribute(LoginFlow.RETURN_TO))
        // Anybody can start logging in, so the session is only kept for a short while
        assertEquals(600, request.session.maxInactiveInterval)

        // Someone logged in already stays logged in as long as they would
        val loggedIn = MockHttpServletRequest("GET", "/beatmaps").apply { session = MockHttpSession().apply { maxInactiveInterval = 1234 } }
        flow.resolve(loggedIn, "osu")
        assertEquals(1234, loggedIn.session.maxInactiveInterval)
        assertThrows<IllegalArgumentException> { flow.resolve(MockHttpServletRequest("GET", "/other"), "unknown") }
    }
}
