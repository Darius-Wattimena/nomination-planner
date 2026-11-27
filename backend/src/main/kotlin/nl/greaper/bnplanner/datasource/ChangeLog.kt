package nl.greaper.bnplanner.datasource

import java.util.concurrent.CopyOnWriteArrayList

/** Remembers when something last changed, since the start of the backend */
class ChangeLog {
    private val versions = mutableMapOf<String, Long>()

    // The last change of each that the planner shows, which can be older than its last change
    private val plannerVersions = mutableMapOf<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var latest = 0L

    val version: Long
        @Synchronized get() = latest

    /**
     * Has to be called after the change is visible, otherwise it could be read too early and missed
     *
     * @param forPlanner false for a change the planner doesn't show, like of a beatmap that wasn't in progress before nor after.
     * It doesn't wake those waiting for the planner.
     */
    fun mark(
        id: String,
        forPlanner: Boolean = true,
    ) {
        synchronized(this) {
            latest++
            versions[id] = latest
            if (forPlanner) {
                plannerVersions[id] = latest
            }
        }

        if (forPlanner) {
            listeners.forEach { it() }
        }
    }

    /** @param forPlanner to only find the changes the planner shows */
    @Synchronized
    fun findSince(
        version: Long,
        forPlanner: Boolean = false,
    ): Set<String> = (if (forPlanner) plannerVersions else versions).filterValues { it > version }.keys

    /**
     * Called after every change the planner shows, while the one making the change waits. So it has to be quick and must not read
     * anything.
     */
    fun listen(listener: () -> Unit) {
        listeners += listener
    }
}
