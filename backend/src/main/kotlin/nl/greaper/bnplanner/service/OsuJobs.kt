package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Calls to osu! that take a while run here one after the other, with a pause in between so osu! isn't asked too much.
 * Nobody waits for them, the website sees the results as they come in.
 */
@Service
class OsuJobs(
    private val beatmapService: BeatmapService,
    private val sleeper: Sleeper = Sleeper.THREAD,
) {
    private val log = KotlinLogging.logger { }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "osu-jobs").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /** How many beatmaps are still to be synced */
    val waiting: Int
        get() = queued.size

    /** @return how many were added, a beatmap that is already waiting is not added again */
    fun syncBeatmaps(
        osuToken: String,
        ids: Collection<String>,
    ): Int {
        val added = ids.filter { queued.add(it) }

        added.forEach { id ->
            executor.execute {
                try {
                    beatmapService.findBeatmap(id)?.let { beatmapService.syncBeatmap(osuToken, it) }
                } catch (ex: Exception) {
                    log.error(ex) { "Unable to sync beatmap $id" }
                } finally {
                    queued.remove(id)
                }
                sleeper.sleep(1_000L + Random.nextInt(0, 1000))
            }
        }

        return added.size
    }

    /** Waits until everything that is queued is done */
    fun finish() {
        executor.submit {}.get()
    }
}
