package nl.greaper.bnplanner.util

/** Waits between calls to osu!, replaceable so tests don't have to wait */
fun interface Sleeper {
    fun sleep(millis: Long)

    companion object {
        val THREAD = Sleeper { Thread.sleep(it) }
    }
}
