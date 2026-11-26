package nl.greaper.bnplanner.client

import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.util.Sleeper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.module.kotlin.jacksonMapperBuilder

class OsuRateLimitTest {
    private var now = 1_000L
    private val sleeps = mutableListOf<Long>()
    private val sleeper =
        Sleeper {
            sleeps += it
            now += it
        }

    private fun tooManyRequests(retryAfter: String? = null) =
        HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too Many Requests",
            HttpHeaders().apply { retryAfter?.let { set("Retry-After", it) } },
            ByteArray(0),
            null,
        )

    @Test
    fun `Should keep calls apart`() {
        val limit = OsuRateLimit(250, sleeper) { now }

        repeat(3) { limit.call("call") {} }
        assertEquals(listOf(250L, 250L), sleeps)

        // Enough time passed already
        now += 1_000
        limit.call("call") {}
        assertEquals(listOf(250L, 250L), sleeps)
    }

    @Test
    fun `Should wait as long as osu asks and then try again`() {
        val limit = OsuRateLimit(0, sleeper) { now }
        val answers = mutableListOf<() -> String>({ throw tooManyRequests("7") }, { throw tooManyRequests(" 2 ") }, { "done" })

        assertEquals("done", limit.call("call") { answers.removeAt(0)() })
        assertEquals(listOf(7_000L, 2_000L), sleeps)
    }

    @Test
    fun `Should wait longer every time when osu doesn't say how long`() {
        val limit = OsuRateLimit(0, sleeper) { now }

        assertThrows<HttpClientErrorException.TooManyRequests> { limit.call("call") { throw tooManyRequests() } }
        assertEquals(listOf(10_000L, 20_000L, 40_000L), sleeps)

        sleeps.clear()
        assertThrows<HttpClientErrorException.TooManyRequests> {
            limit.call("call") {
                throw mock<HttpClientErrorException.TooManyRequests> {
                    on {
                        responseHeaders
                    } doReturn null
                }
            }
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L), sleeps)

        sleeps.clear()
        val wrong = listOf("soon", "-5", "")
        var attempt = 0
        assertThrows<HttpClientErrorException.TooManyRequests> { limit.call("call") { throw tooManyRequests(wrong[attempt++ % 3]) } }
        assertEquals(listOf(10_000L, 20_000L, 40_000L), sleeps)
    }

    @Test
    fun `Should not wait longer than a minute when osu doesn't say, or five when it asks for more`() {
        val limit = OsuRateLimit(0, sleeper) { now }
        val unknown =
            OsuRateLimit::class.java.getDeclaredMethod("findWait", String::class.java, Int::class.java).apply {
                isAccessible =
                    true
            }

        assertEquals(60_000L, unknown.invoke(limit, null, 5))
        assertEquals(300_000L, unknown.invoke(limit, "3600", 0))
    }

    @Test
    fun `Should make others wait as well`() {
        val limit = OsuRateLimit(0, sleeper) { now }
        var refused = false

        limit.call("first") {
            if (!refused) {
                refused = true
                throw tooManyRequests("4")
            }
        }
        assertEquals(listOf(4_000L), sleeps)
    }

    @Test
    fun `Should not retry other errors`() {
        val limit = OsuRateLimit(0, sleeper) { now }
        var calls = 0

        assertThrows<IllegalStateException> {
            limit.call("call") {
                calls++
                throw IllegalStateException("broken")
            }
        }
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `Should retry every call to osu`() {
        Planner()
        FakeOsu.respond("GET /api/v2/users/5?key=id", """{"id":5,"username":"BN"}""")
        FakeOsu.refuse("GET /api/v2/users/5?key=id", 2, retryAfter = "1")
        FakeOsu.respond("GET /groups/history?group_id=28", """{"events":[],"cursor_string":null}""")
        FakeOsu.refuse("GET /groups/history?group_id=28", 1)
        FakeOsu.refuse("POST /oauth/token", 4)
        val client =
            OsuHttpClient(
                OsuProperties("14229", "secret", "", FakeOsu.url, minRequestIntervalMillis = 0),
                jacksonMapperBuilder().build(),
                sleeper,
            ) { now }

        assertEquals("BN", client.findUserWithId("token", "5")!!.username)
        assertEquals(emptyList<Any>(), client.findGroupHistory("28", null).events)
        assertNull(client.findClientToken())
        assertEquals(listOf(1_000L, 1_000L, 10_000L, 10_000L, 20_000L, 40_000L), sleeps)
        assertEquals(3 + 2 + 4, FakeOsu.requests.size)
    }

    @Test
    fun `Should keep calls a quarter of a second apart by default`() {
        assertEquals(250, OsuProperties("14229", "", "").minRequestIntervalMillis)
    }
}
