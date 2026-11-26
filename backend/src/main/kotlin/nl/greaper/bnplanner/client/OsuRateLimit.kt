package nl.greaper.bnplanner.client

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.web.client.HttpClientErrorException

/**
 * Keeps the calls to osu! apart, and waits as long as osu! asks when it answers "too many requests" before trying again.
 * Every call waits for it, also the ones of other threads, as osu! counts all calls of the planner together.
 */
class OsuRateLimit(
    private val minIntervalMillis: Long,
    private val sleeper: Sleeper,
    private val clock: () -> Long,
) {
    companion object {
        const val MAX_RETRIES = 3
        const val FIRST_WAIT_MILLIS = 10_000L
        const val MAX_WAIT_MILLIS = 60_000L

        // Some protection against a wrong header
        const val MAX_ASKED_WAIT_MILLIS = 300_000L
    }

    private val log = KotlinLogging.logger { }
    private var nextAllowed = 0L

    fun <T> call(
        name: String,
        block: () -> T,
    ): T {
        var attempt = 0

        while (true) {
            waitForTurn()

            try {
                return block()
            } catch (ex: HttpClientErrorException.TooManyRequests) {
                if (attempt >= MAX_RETRIES) {
                    log.warn { "osu! still refuses $name after $MAX_RETRIES retries, too many requests" }
                    throw ex
                }

                val wait = findWait(ex.responseHeaders?.getFirst("Retry-After"), attempt)
                log.warn { "osu! refuses $name, too many requests. Trying again in ${wait / 1000.0} s (${attempt + 1}/$MAX_RETRIES)" }
                delayEveryone(wait)
                attempt++
            }
        }
    }

    private fun waitForTurn() {
        val wait =
            synchronized(this) {
                val now = clock()
                val turn = maxOf(now, nextAllowed)
                nextAllowed = turn + minIntervalMillis
                turn - now
            }

        if (wait > 0) {
            sleeper.sleep(wait)
        }
    }

    @Synchronized
    private fun delayEveryone(millis: Long) {
        nextAllowed = maxOf(nextAllowed, clock() + millis)
    }

    /** The seconds osu! asks for, otherwise longer every time */
    private fun findWait(
        retryAfter: String?,
        attempt: Int,
    ): Long {
        val asked = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

        return if (asked != null) {
            minOf(asked * 1000, MAX_ASKED_WAIT_MILLIS)
        } else {
            minOf(FIRST_WAIT_MILLIS shl attempt, MAX_WAIT_MILLIS)
        }
    }
}
