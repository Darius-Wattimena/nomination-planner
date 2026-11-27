package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.service.OsuJobs
import nl.greaper.bnplanner.service.OsuService
import nl.greaper.bnplanner.util.isInProgress
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the beatmaps in progress up to date with osu!, a few at a time, the ones that were checked longest ago first.
 * A safety net for anything the followed beatmap events miss.
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
        // The syncs use the planner's own login with osu!, nothing works without it
        osuService.findClientToken() ?: return emptyList()
        val next =
            beatmapDataSource
                .findAll { it.isInProgress() }
                .sortedBy { checked[it.osuId] ?: Instant.EPOCH }
                .take(perMinute)
                .map { it.osuId }

        next.forEach { checked[it] = Instant.now() }
        jobs.syncBeatmaps(next)

        return next
    }
}
