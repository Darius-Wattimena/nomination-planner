package nl.greaper.bnplanner.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BackoffTest {
    private var now = Instant.parse("2026-11-26T19:00:00Z")
    private val backoff = Backoff { now }

    @Test
    fun `Should wait twice as long after each failure, up to an hour`() {
        assertFalse(backoff.isWaiting())

        assertEquals(listOf(60L, 120, 240, 480, 960, 1920, 3600, 3600), List(8) { backoff.failed() })

        assertTrue(backoff.isWaiting())
        now = now.plusSeconds(3599)
        assertTrue(backoff.isWaiting())
        now = now.plusSeconds(1)
        assertFalse(backoff.isWaiting())
    }

    @Test
    fun `Should start over after it worked`() {
        backoff.failed()
        backoff.failed()
        backoff.succeeded()

        assertFalse(backoff.isWaiting())
        assertEquals(60, backoff.failed())
    }

    @Test
    fun `Should use the clock by default`() {
        val backoff = Backoff()
        backoff.failed()

        assertTrue(backoff.isWaiting())
    }
}
