package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.OsuJobs
import nl.greaper.bnplanner.service.OsuService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the beatmaps in progress up to date with osu!, a few at a time, the ones that were checked longest ago first.
 * Takes the place of Aiess until there is something that tells the planner about changes.
 */
@Component
@ConditionalOnProperty(name = ["osu.background-sync.enabled"], havingValue = "true")
class BackgroundSync(
    private val beatmapDataSource: BeatmapDataSource,
    private val osuService: OsuService,
    private val jobs: OsuJobs,
    @Value("\${osu.background-sync.per-minute}") private val perMinute: Int,
) {
    private val checked = ConcurrentHashMap<String, Instant>()

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun sync(): List<String> {
        // Also the planner's own login with osu!, so it works without anybody using the planner
        val token = osuService.findClientToken() ?: return emptyList()
        val next =
            beatmapDataSource
                .findAll { it.status != BeatmapStatus.Ranked && it.status != BeatmapStatus.Graved }
                .sortedBy { checked[it.osuId] ?: Instant.EPOCH }
                .take(perMinute)
                .map { it.osuId }

        next.forEach { checked[it] = Instant.now() }
        jobs.syncBeatmaps(token, next)

        return next
    }
}
