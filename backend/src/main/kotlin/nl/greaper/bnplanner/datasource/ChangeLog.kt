package nl.greaper.bnplanner.datasource

import java.util.concurrent.CopyOnWriteArrayList

/** Remembers when something last changed, since the start of the backend */
class ChangeLog {
    private val versions = mutableMapOf<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var latest = 0L

    val version: Long
        @Synchronized get() = latest

    /** Has to be called after the change is visible, otherwise it could be read too early and missed */
    fun mark(id: String) {
        synchronized(this) {
            latest++
            versions[id] = latest
        }

        listeners.forEach { it() }
    }

    @Synchronized
    fun findSince(version: Long): Set<String> = versions.filterValues { it > version }.keys

    /** Called after every change, while the one making the change waits. So it has to be quick and must not read anything. */
    fun listen(listener: () -> Unit) {
        listeners += listener
    }
}
