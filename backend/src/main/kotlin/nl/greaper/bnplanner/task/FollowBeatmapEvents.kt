package nl.greaper.bnplanner.task

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.FollowedDataSource
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.service.BeatmapService
import nl.greaper.bnplanner.service.OsuJobs
import nl.greaper.bnplanner.service.OsuService
import nl.greaper.bnplanner.util.Backoff
import nl.greaper.bnplanner.util.EventPages
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Follows the nominations, qualifications, ranks, resets and disqualifications on osu! (https://osu.ppy.sh/beatmapsets/events)
 * of the beatmaps on the planner. Takes the place of Aiess.
 */
@Component
@ConditionalOnProperty(name = ["osu.follow-events.enabled"], havingValue = "true")
class FollowBeatmapEvents(
    private val followed: FollowedDataSource,
    private val osuService: OsuService,
    private val osuClient: OsuHttpClient,
    private val beatmapService: BeatmapService,
    private val beatmapDataSource: BeatmapDataSource,
    private val jobs: OsuJobs,
    sleeper: Sleeper = Sleeper.THREAD,
    private val backoff: Backoff = Backoff(),
) {
    private val log = KotlinLogging.logger { }
    private val pages = EventPages<BeatmapsetEvent>(sleeper, MAX_PAGES) { it.id }

    /** @return how many beatmaps were changed */
    @Scheduled(
        fixedDelayString = "\${osu.follow-events.every-seconds}",
        initialDelayString = "\${osu.follow-events.every-seconds}",
        timeUnit = TimeUnit.SECONDS,
    )
    fun follow(): Int {
        if (backoff.isWaiting()) {
            return 0
        }

        return try {
            followNewer().also { backoff.succeeded() }
        } catch (ex: Exception) {
            val seconds = backoff.failed()
            log.warn(ex) { "Could not follow the beatmap events of osu!, trying again in $seconds seconds" }
            0
        }
    }

    private fun followNewer(): Int {
        // Also the planner's own login with osu!, so it works without anybody using the planner
        val token = checkNotNull(osuService.findClientToken()) { "osu! gave no token to the planner" }
        val last = followed.findLastId(NAME)

        if (last == null) {
            // What happened before is known from adding and syncing the beatmaps
            val newest = osuClient.findBeatmapsetEvents(token, 1).events.maxOfOrNull { it.id } ?: return 0
            followed.saveLastId(NAME, newest)
            log.info { "Following the beatmap events of osu! from event $newest on" }
            return 0
        }

        val found = pages.findNewer(last) { index -> osuClient.findBeatmapsetEvents(token, index + 1).events }

        if (!found.complete) {
            // osu! knows how the beatmaps are now
            val inProgress =
                beatmapDataSource
                    .findAll { it.status != BeatmapStatus.Ranked && it.status != BeatmapStatus.Graved }
                    .map { it.osuId }
            log.warn { "Too many beatmap events of osu! after event $last to read, syncing ${inProgress.size} beatmaps instead" }
            jobs.syncBeatmaps(token, inProgress)
        }

        return found.events.count { event ->
            val changed = beatmapService.applyOsuEvent(token, event)
            followed.saveLastId(NAME, event.id)

            if (changed != null && isMainGamemodeUnknown(changed, event)) {
                // Only osu! knows which gamemode needs two nominations, see findMainGamemodes
                jobs.syncBeatmaps(token, listOf(changed.osuId))
            }

            changed != null
        }
    }

    private fun isMainGamemodeUnknown(
        beatmap: Beatmap,
        event: BeatmapsetEvent,
    ): Boolean = event.kind == BeatmapsetEventType.NOMINATE && beatmap.gamemodes.size > 1 && beatmap.mainGamemodes?.size != 1

    companion object {
        const val NAME = "beatmapset-events"
        const val MAX_PAGES = 10
    }
}
