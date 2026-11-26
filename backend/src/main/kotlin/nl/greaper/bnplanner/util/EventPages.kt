package nl.greaper.bnplanner.util

/** Reads pages of events, newest first, back to the last event handled */
class EventPages<E>(
    private val sleeper: Sleeper,
    private val maxPages: Int,
    private val id: (E) -> Long,
) {
    /**
     * @param events oldest first
     * @param complete false when there were more events in between than [maxPages] could hold
     */
    data class Found<E>(
        val events: List<E>,
        val complete: Boolean,
    )

    /** @param page the events of a page, starting at 0, null when there are no more pages */
    fun findNewer(
        last: Long,
        page: (index: Int) -> List<E>?,
    ): Found<E> {
        val newer = mutableListOf<E>()

        for (index in 0 until maxPages) {
            if (index > 0) {
                // Asking for one page after the other is fine, but not all at once
                sleeper.sleep(1_000)
            }

            val events = page(index) ?: return found(newer, true)
            newer += events.filter { id(it) > last }

            if (events.isEmpty() || events.any { id(it) <= last }) {
                return found(newer, true)
            }
        }

        return found(newer, false)
    }

    // A page can show an event again, when newer ones moved it to the next page
    private fun found(
        events: List<E>,
        complete: Boolean,
    ) = Found(events.distinctBy(id).sortedBy(id), complete)
}
