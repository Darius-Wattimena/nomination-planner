package nl.greaper.bnplanner.auth

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    @Test
    fun `Should ignore missing values in a list of audiences`() {
        whenever(client.findTokenOwnerId(any())) doReturn "123"

        assertEquals("123", verifier.verify(createToken(audience = "[null,\"1\",\"14229\"]")))
        assertNull(verifier.verify(createToken(audience = "[null]")))
        assertNull(verifier.verify(createToken(audience = "[]")))
    }

    @Test
    fun `Should reject a token without a subject`() {
        val token = createToken().split(".").toMutableList()
        token[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aud":"14229"}""".toByteArray())

        assertNull(verifier.verify(token.joinToString(".")))
    }

    @Test
    fun `Should remember a rejected token for a minute`() {
        val token = createToken()
        whenever(client.findTokenOwnerId(token)) doReturn null
        assertNull(verifier.verify(token))

        whenever(client.findTokenOwnerId(token)) doReturn "123"
        clock.now += OsuTokenVerifier.INVALID_DURATION - Duration.ofSeconds(1)
        assertNull(verifier.verify(token))
        clock.now += Duration.ofSeconds(1)
        assertEquals("123", verifier.verify(token))
    }

    @Test
    fun `Should only ask osu once when several calls use the same token`() {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val token = createToken()
        whenever(client.findTokenOwnerId(token)).thenAnswer {
            asked.countDown()
            answer.await(10, TimeUnit.SECONDS)
            "123"
        }

        val results = ConcurrentLinkedQueue<String>()
        val first = thread { results += verifier.verify(token) }
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        val second = thread { results += verifier.verify(token) }
        // The second call has to wait for the first one
        while (second.state != Thread.State.BLOCKED) Thread.onSpinWait()
        answer.countDown()
        listOf(first, second).forEach { it.join(10_000) }

        assertEquals(listOf("123", "123"), results.toList())
        verify(client, times(1)).findTokenOwnerId(any())
    }

    /** Counts the calls itself, as Mockito remembers every call */
    private class CountingClient : OsuHttpClient(OsuProperties("14229", "", ""), ObjectMapper()) {
        var calls = 0
        var owner: (String) -> String? = { "123" }

        override fun findTokenOwnerId(token: String): String? {
            calls++
            return owner(token)
        }
    }

    private fun createTokens(amount: Int) = (1..amount).map { createToken(expires = 4102444800 + it) }

    @Test
    fun `Should only forget outdated verifications when remembering too many`() {
        val client = CountingClient()
        val verifier = OsuTokenVerifier(client, OsuProperties("14229", "", ""), clock)
        val valid = createToken()
        assertEquals("123", verifier.verify(valid))
        client.owner = { null }
        createTokens(OsuTokenVerifier.MAX_CACHED_TOKENS - 1).forEach { assertNull(verifier.verify(it)) }

        clock.now += OsuTokenVerifier.INVALID_DURATION
        assertNull(verifier.verify(createToken(expires = 4102444799)))
        val calls = client.calls
        assertEquals(OsuTokenVerifier.MAX_CACHED_TOKENS + 1, calls)
        assertEquals("123", verifier.verify(valid))
        assertEquals(calls, client.calls)
    }

    @Test
    fun `Should forget every verification when too many are still valid`() {
        val client = CountingClient()
        val verifier = OsuTokenVerifier(client, OsuProperties("14229", "", ""), clock)
        val tokens = createTokens(OsuTokenVerifier.MAX_CACHED_TOKENS + 1)
        tokens.dropLast(1).forEach { assertEquals("123", verifier.verify(it)) }
        assertEquals("123", verifier.verify(tokens.first()))
        assertEquals(OsuTokenVerifier.MAX_CACHED_TOKENS, client.calls)

        verifier.verify(tokens.last())
        verifier.verify(tokens.first())
        assertEquals(OsuTokenVerifier.MAX_CACHED_TOKENS + 2, client.calls)
        verifier.verify(tokens.last())
        assertEquals(OsuTokenVerifier.MAX_CACHED_TOKENS + 2, client.calls)
    }
}
