package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import org.springframework.stereotype.Service

/** Syncs that nobody waits for, the planner shows the results as they come in */
@Service
class SyncService(
    private val userService: UserService,
    private val beatmapService: BeatmapService,
    private val jobs: OsuJobs,
) {
    private val log = KotlinLogging.logger { }

    data class Queued(
        val queued: Int,
    )

    /** @param status of the beatmaps in progress to sync, all of them when empty */
    fun syncPendingBeatmaps(status: Set<BeatmapStatus>): Queued = Queued(jobs.syncBeatmaps(beatmapService.findPendingIds(status)))

    /** How many beatmaps and users are still waiting to be synced */
    fun findWaiting(): Int = jobs.waiting

    /** @param force to also sync the users the planner already knows, otherwise only the unknown and restricted ones */
    fun syncUsers(
        users: Set<String>,
        force: Boolean,
    ): Queued {
        val needed =
            users.filter { id ->
                val known = userService.findUserById(id)
                force || known == null || known.restricted == true
            }
        log.info { "Syncing ${needed.size} of ${users.size} users" }

        return Queued(jobs.syncUsers(needed))
    }
}
