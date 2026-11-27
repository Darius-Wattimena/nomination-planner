package nl.greaper.bnplanner.client

/** How long a call to osu! may wait for its turn, like after osu! answered "too many requests" */
enum class OsuWait(
    val maxMillis: Long,
) {
    /** Someone is waiting for the answer, they rather hear osu! is unavailable than wait minutes */
    REQUEST(5_000),

    /** Nobody is waiting for the answer, like the jobs, so it waits as long as osu! asks */
    BACKGROUND(Long.MAX_VALUE),
}
