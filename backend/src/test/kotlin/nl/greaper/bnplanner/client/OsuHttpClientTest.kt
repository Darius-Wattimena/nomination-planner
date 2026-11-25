package nl.greaper.bnplanner.client

import com.fasterxml.jackson.databind.ObjectMapper
import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.config.OsuProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

class OsuHttpClientTest {
    private val client = OsuHttpClient(OsuProperties("14229", "", ""), ObjectMapper())
    private val server = MockRestServiceServer.bindTo(client.verifyRest).build()

    @Test
    fun `Should find the owner of a token`() {
        val body =
            """{"id":123,"username":"Someone","is_restricted":false,"statistics":{"pp":1.5},""" +
                """"groups":[{"id":28,"identifier":"bng","name":"BN","playmodes":["osu"],"colour":null}]}"""

        server
            .expect(requestTo("https://osu.ppy.sh/api/v2/me"))
            .andExpect(header("Authorization", "Bearer token"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        assertEquals("123", client.findTokenOwnerId("token"))
    }

    @Test
    fun `Should find no owner when osu refuses the token`() {
        server.expect(requestTo("https://osu.ppy.sh/api/v2/me")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertNull(client.findTokenOwnerId("token"))
    }

    @Test
    fun `Should be unavailable when osu is rate limiting or failing`() {
        server.expect(requestTo("https://osu.ppy.sh/api/v2/me")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo("https://osu.ppy.sh/api/v2/me")).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }
        assertThrows<OsuUnavailableException> { client.findTokenOwnerId("token") }
    }
}
