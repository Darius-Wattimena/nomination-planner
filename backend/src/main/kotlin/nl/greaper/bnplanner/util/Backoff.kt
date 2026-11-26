package nl.greaper.bnplanner.util

import java.time.Instant

/** Waits longer after each failure, up to an hour, so osu! isn't asked again and again while something is wrong */
class Backoff(
    private val now: () -> Instant = Instant::now,
) {
    private var failures = 0
    private var until = Instant.EPOCH

    /** Whether it is too early to try again */
    @Synchronized
    fun isWaiting(): Boolean = now() < until

    /** @return how many seconds to wait: one minute, then twice as long each time */
    @Synchronized
    fun failed(): Long {
        failures++
        val seconds = minOf(MAX_SECONDS, 60L shl minOf(failures - 1, 6))
        until = now().plusSeconds(seconds)
        return seconds
    }

    @Synchronized
    fun succeeded() {
        failures = 0
        until = Instant.EPOCH
    }

    companion object {
        const val MAX_SECONDS = 3600L
    }
}
