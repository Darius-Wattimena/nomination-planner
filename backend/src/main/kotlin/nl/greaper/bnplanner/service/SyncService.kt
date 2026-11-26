package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.stereotype.Service
import kotlin.random.Random
import kotlin.system.measureTimeMillis

@Service
class SyncService(
    private val userService: UserService,
    private val beatmapService: BeatmapService,
    private val jobs: OsuJobs,
    private val sleeper: Sleeper = Sleeper.THREAD,
) {
    val log = KotlinLogging.logger { }

    /** Nobody waits for it, the planner shows the results as they come in */
    fun syncPendingBeatmaps(
        osuToken: String,
        status: Set<BeatmapStatus>,
    ): SyncInfo {
        val beatmaps =
            beatmapService.findBeatmapsIds(
                search = null,
                artist = null,
                title = null,
                mapper = null,
                status = status,
                nominators = emptySet(),
                page = BeatmapPage.PENDING,
                gamemodes = emptySet(),
                missingNominator = emptySet(),
            )

        return SyncInfo(duration = 0, totalSynced = jobs.syncBeatmaps(osuToken, beatmaps))
    }

    fun findWaiting(): Int = jobs.waiting

    fun syncSingleBeatmap(
        osuToken: String,
        beatmapId: String,
    ): SyncInfo {
        log.info { "Syncing beatmap (id = $beatmapId)" }
        var synced = false
        val duration =
            measureTimeMillis {
                synced = syncBeatmapById(osuToken, beatmapId)
            }
        log.info { "Synced beatmap in $duration ms (id = $beatmapId)" }

        return SyncInfo(
            duration = duration,
            totalSynced = if (synced) 1 else 0,
        )
    }

    /** Whether the beatmap is on the planner */
    private fun syncBeatmapById(
        osuToken: String,
        beatmapId: String,
    ): Boolean {
        val beatmap = beatmapService.findBeatmap(beatmapId) ?: return false
        beatmapService.syncBeatmap(osuToken, beatmap)
        return true
    }

    fun syncUsers(
        osuToken: String,
        users: Set<String>,
        force: Boolean = false,
    ): SyncInfo {
        val totalUsers = users.count()

        log.info { "Checking $totalUsers users." }

        val duration =
            measureTimeMillis {
                users.forEachIndexed { index, userId ->
                    val currentUser = userService.findUserById(userId)
                    if (force || currentUser == null || currentUser.restricted == true) {
                        userService.forceFindUserById(osuToken, userId)
                        log.info { "[${index + 1}/$totalUsers] User $userId synced, sleeping." }
                        sleeper.sleep(1_000L + Random.nextInt(0, 1000))
                    } else {
                        log.info { "[${index + 1}/$totalUsers] User ${currentUser.username} is already good, skipping." }
                    }
                }
            }

        return SyncInfo(
            duration = duration,
            totalSynced = totalUsers,
        )
    }

    data class SyncInfo(
        val duration: Long,
        val totalSynced: Int,
    )
}
