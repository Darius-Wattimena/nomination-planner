package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.PlannerUpdate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers as soon as something changed, or after a while when nothing did. So the website shows changes right away, without asking
 * over and over. Works through every proxy, unlike a connection that stays open.
 */
@Service
class LiveUpdates(
    private val service: PlannerService,
    beatmapDataSource: BeatmapDataSource,
    userDataSource: UserDataSource,
    @Value("\${planner.wait-millis}") private val waitMillis: Long,
) {
    private class Waiter(
        val since: String,
        val result: DeferredResult<PlannerUpdate>,
    )

    private val waiting = ConcurrentLinkedQueue<Waiter>()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "live-updates").apply { isDaemon = true } }
    private val isWakeScheduled = AtomicBoolean(false)

    init {
        beatmapDataSource.changes.listen { changed() }
        userDataSource.changes.listen { changed() }
    }

    fun wait(since: String?): DeferredResult<PlannerUpdate> {
        val result = DeferredResult<PlannerUpdate>(waitMillis)
        val update = service.find(since)

        if (since == null || update.isComplete || update.hasContent()) {
            result.setResult(update)
            return result
        }

        val waiter = Waiter(since, result)
        result.onTimeout { result.setResult(service.find(since)) }
        result.onCompletion { waiting.remove(waiter) }
        waiting += waiter
        // Something could have changed right before it was waiting
        changed()

        return result
    }

    /** Several changes at once, like a sync, are sent together */
    private fun changed() {
        if (isWakeScheduled.compareAndSet(false, true)) {
            executor.schedule({ wake() }, 50, TimeUnit.MILLISECONDS)
        }
    }

    private fun wake() {
        isWakeScheduled.set(false)
        // Many wait since the same change
        val updates = mutableMapOf<String, PlannerUpdate>()
        waiting.forEach { waiter ->
            val update = updates.getOrPut(waiter.since) { service.find(waiter.since) }

            if (update.hasContent()) {
                waiter.result.setResult(update)
            }
        }
    }

    private fun PlannerUpdate.hasContent(): Boolean = beatmaps.isNotEmpty() || users.isNotEmpty() || removed.isNotEmpty()
}
