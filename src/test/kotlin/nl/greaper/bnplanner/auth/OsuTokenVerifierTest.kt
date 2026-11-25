package nl.greaper.bnplanner.auth

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.config.OsuProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

class OsuTokenVerifierTest {
    private class TestClock(
        var now: Instant = Instant.now(),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val client = mock<OsuHttpClient>()
    private val clock = TestClock()
    private val verifier = OsuTokenVerifier(client, OsuProperties("14229", "", ""), clock)

    companion object {
        fun createToken(
            subject: String = "123",
            audience: String = "\"14229\"",
            expires: Long = 4102444800,
            algorithm: String = "RS256",
        ): String {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
            val header = encode("""{"alg":"$algorithm","typ":"JWT"}""")
            val body = encode("""{"sub":"$subject","aud":$audience,"exp":$expires}""")
            return "$header.$body.made-up-signature"
        }
    }

    @Test
    fun `Should accept a token that osu accepts`() {
        val token = createToken()
        whenever(client.findTokenOwnerId(token)) doReturn "123"

        assertEquals("123", verifier.verify(token))
        assertEquals("123", verifier.verify(token))
        verify(client, times(1)).findTokenOwnerId(any())
    }

    @Test
    fun `Should reject a forged token that osu does not accept`() {
        whenever(client.findTokenOwnerId(any())) doReturn null

        assertNull(verifier.verify(createToken()))
        assertNull(verifier.verify(createToken(algorithm = "none")))
    }

    @Test
    fun `Should reject a token that belongs to someone else`() {
        whenever(client.findTokenOwnerId(any())) doReturn "999"

        assertNull(verifier.verify(createToken(subject = "123")))
    }

    @Test
    fun `Should reject tokens without asking osu when they are of another application, expired or malformed`() {
        assertNull(verifier.verify(createToken(audience = "\"1\"")))
        assertNull(verifier.verify(createToken(audience = "null")))
        assertNull(verifier.verify(createToken(expires = 1)))
        assertNull(verifier.verify(createToken(subject = "")))
        assertNull(verifier.verify("not-a-token"))

        verify(client, never()).findTokenOwnerId(any())
    }

    @Test
    fun `Should accept an audience provided as list or number`() {
        whenever(client.findTokenOwnerId(any())) doReturn "123"

        assertEquals("123", verifier.verify(createToken(audience = "[\"14229\"]")))
        assertEquals("123", verifier.verify(createToken(audience = "14229")))
    }

    @Test
    fun `Should ask osu again once the verification is outdated`() {
        val token = createToken()
        whenever(client.findTokenOwnerId(token)) doReturn "123"
        assertEquals("123", verifier.verify(token))

        // The token is revoked
        whenever(client.findTokenOwnerId(token)) doReturn null
        clock.now += OsuTokenVerifier.VALID_DURATION + Duration.ofSeconds(1)

        assertNull(verifier.verify(token))
    }

    @Test
    fun `Should not remember that osu was unavailable`() {
        val token = createToken()
        whenever(client.findTokenOwnerId(token))
            .doThrow(OsuUnavailableException(RuntimeException()))
            .doReturn("123")

        assertThrows<OsuUnavailableException> { verifier.verify(token) }
        assertEquals("123", verifier.verify(token))
    }
}
