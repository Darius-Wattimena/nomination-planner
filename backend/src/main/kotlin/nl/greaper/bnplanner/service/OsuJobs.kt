package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Calls to osu! that take a while run here one after the other, the rate limit of the osu! client keeps them apart.
 * Nobody waits for them, the website sees the results as they come in. They use the planner's own login with osu!,
 * so they don't depend on the login of whoever asked for them.
 */
@Service
class OsuJobs(
    private val beatmapService: BeatmapService,
    private val userService: UserService,
    private val osuService: OsuService,
) {
    private val log = KotlinLogging.logger { }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "osu-jobs").apply { isDaemon = true } }
    private val queuedBeatmaps = ConcurrentHashMap.newKeySet<String>()
    private val queuedUsers = ConcurrentHashMap.newKeySet<String>()

    /** How many beatmaps and users are still to be synced */
    val waiting: Int
        get() = queuedBeatmaps.size + queuedUsers.size

    /** @return how many were added, a beatmap that is already waiting is not added again */
    fun syncBeatmaps(ids: Collection<String>): Int =
        queue("beatmap", ids, queuedBeatmaps) { token, id ->
            beatmapService.findBeatmap(id)?.let { beatmapService.syncBeatmap(token, it) }
        }

    /** @return how many were added, a user that is already waiting is not added again */
    fun syncUsers(ids: Collection<String>): Int =
        queue("user", ids, queuedUsers) { token, id ->
            userService.forceFindUserById(token, id, logEventToDiscord = false)
            log.debug { "User $id synced" }
        }

    private fun queue(
        kind: String,
        ids: Collection<String>,
        queued: MutableSet<String>,
        sync: (token: String, id: String) -> Unit,
    ): Int {
        val added = ids.filter { queued.add(it) }

        added.forEach { id ->
            executor.execute {
                try {
                    sync(checkNotNull(osuService.findClientToken()) { "osu! gave no token to the planner" }, id)
                } catch (ex: Exception) {
                    log.error(ex) { "Unable to sync $kind $id" }
                } finally {
                    queued.remove(id)
                }
            }
        }

        return added.size
    }

    /** Waits until everything that is queued is done */
    fun finish() {
        executor.submit {}.get()
    }

    /** What is still queued is left for the syncs after the restart */
    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
