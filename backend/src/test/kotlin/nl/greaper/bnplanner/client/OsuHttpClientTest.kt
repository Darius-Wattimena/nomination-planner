package nl.greaper.bnplanner.client

import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.support.FakeOsu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

class OsuHttpClientTest {
    private val client = OsuHttpClient(OsuProperties("14229", "", "", FakeOsu.url), JsonMapper())

    init {
        FakeOsu.reset()
    }

    @Test
    fun `Should find the owner of a token`() {
        val body =
            """{"id":123,"username":"Someone","is_restricted":false,"statistics":{"pp":1.5},""" +
                """"groups":[{"id":28,"identifier":"bng","name":"BN","playmodes":["osu"],"colour":null}]}"""
        FakeOsu.respond("GET /api/v2/me", body)

        assertEquals("123", client.findTokenOwnerId("token"))
        assertEquals("Bearer token", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should find no owner when osu refuses the token`() {
        FakeOsu.respond("GET /api/v2/me", "", 401)
        assertNull(client.findTokenOwnerId("token"))

        FakeOsu.respond("GET /api/v2/me", "", 403)
        assertNull(client.findTokenOwnerId("token"))
    }

    @Test
    fun `Should be unavailable when osu is rate limiting or failing`() {
        FakeOsu.respond("GET /api/v2/me", "", 429)
        assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }

        FakeOsu.respond("GET /api/v2/me", "", 502)
        assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }
    }
}
