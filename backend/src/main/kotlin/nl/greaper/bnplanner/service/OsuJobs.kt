package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import nl.greaper.bnplanner.model.Gamemode
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private val queuedImports = ConcurrentHashMap.newKeySet<String>()
    private val queuedSearches = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var stopping = false

    /** How many beatmaps, users, imports and searches for them are still to be done */
    val waiting: Int
        get() = queuedBeatmaps.size + queuedUsers.size + queuedImports.size + queuedSearches.size

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

    /**
     * @param found who nominated which gamemode of each beatmap on osu!, oldest first
     * @return how many were added, a beatmap that is already waiting is not added again
     */
    fun importBeatmaps(found: Map<String, Map<Gamemode, List<String>>>): Int =
        queue("import", found.keys, queuedImports) { token, id ->
            beatmapService.importBeatmap(token, id, found[id].orEmpty())
        }

    /**
     * Something that finds out what to sync or import, like a search through the events of osu!
     *
     * @return false when the same search is already waiting
     */
    fun search(
        name: String,
        search: (token: String) -> Unit,
    ): Boolean = queue("search", listOf(name), queuedSearches) { token, _ -> search(token) } == 1

    private fun queue(
        kind: String,
        ids: Collection<String>,
        queued: MutableSet<String>,
        sync: (token: String, id: String) -> Unit,
    ): Int {
        val added = ids.filter { queued.add(it) }
        var count = 0

        for (id in added) {
            try {
                executor.execute { run(kind, id, queued, sync) }
                count++
            } catch (ex: RejectedExecutionException) {
                // Stopping, left for the syncs after the restart
                queued.removeAll(added.drop(count).toSet())
                log.debug { "Stopping, not syncing ${added.size - count} more of kind $kind" }
                break
            }
        }

        return count
    }

    private fun run(
        kind: String,
        id: String,
        queued: MutableSet<String>,
        sync: (token: String, id: String) -> Unit,
    ) {
        try {
            sync(checkNotNull(osuService.findClientToken()) { "osu! gave no token to the planner" }, id)
        } catch (ex: Exception) {
            if (stopping) {
                log.info { "Stopped syncing $kind $id, the planner is stopping" }
            } else {
                log.error(ex) { "Unable to sync $kind $id" }
            }
        } finally {
            queued.remove(id)
        }
    }

    /** Waits until everything that is queued is done, also what a search queued meanwhile */
    fun finish() {
        do {
            executor.submit {}.get()
        } while (waiting > 0)
    }

    /** What is still queued is left for the syncs after the restart */
    @PreDestroy
    fun stop() {
        stopping = true
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
