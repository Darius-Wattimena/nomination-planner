package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.model.osu.MeGroup
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset

/** Syncs that nobody waits for, the planner shows the results as they come in */
@Service
class SyncService(
    private val userService: UserService,
    private val beatmapService: BeatmapService,
    private val jobs: OsuJobs,
    private val osuClient: OsuHttpClient,
) {
    private val log = KotlinLogging.logger { }

    data class Queued(
        val queued: Int,
    )

    data class Searching(
        /** False when the same search is still going */
        val started: Boolean,
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

    /**
     * Syncs everyone in the BN, probationary BN and NAT on osu!, and everyone the planner still has in one of them, who may have left
     *
     * @throws nl.greaper.bnplanner.client.OsuUnavailableException when osu! didn't tell who is in one of the groups
     */
    fun syncGroupMembers(): Queued {
        val members = MeGroup.SupportedGroups.flatMap { osuClient.findGroupMembers(it) }.toSet()
        val onPlanner = userService.findUsersInGroups().map { it.osuId }.toSet()
        val users = members + onPlanner
        log.info { "Syncing ${members.size} members of the groups on osu!, and ${(onPlanner - members).size} who are no longer in them" }

        return Queued(jobs.syncUsers(users))
    }

    /**
     * Looks through the nominations on osu! of the last days in the background, and imports the beatmaps that are not on the planner
     * one by one afterwards. Those osu! no longer has in progress or ranked are left out.
     */
    fun importMissingBeatmaps(days: Int): Searching {
        val since = LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong())

        return Searching(jobs.search("nominations") { token -> jobs.importBeatmaps(findMissingBeatmaps(token, since)) })
    }

    /** Who nominated which gamemode of the beatmaps not on the planner, oldest first, by their id */
    internal fun findMissingBeatmaps(
        token: String,
        since: LocalDate,
    ): Map<String, Map<Gamemode, List<String>>> {
        val start = since.atStartOfDay(ZoneOffset.UTC).toInstant()
        val nominations = mutableListOf<BeatmapsetEvent>()

        for (page in 1..MAX_PAGES) {
            val events = osuClient.findNominations(token, since, page).events
            nominations += events.filter { it.createdAt >= start }

            // Newest first, so an older one shows where it ends, also when osu! ignores the start
            if (events.isEmpty() || events.any { it.createdAt < start }) {
                break
            }
            if (page == MAX_PAGES) {
                log.warn { "Stopped looking for nominations on osu! after $MAX_PAGES pages, at ${events.last().createdAt}" }
            }
        }

        val missing =
            nominations
                .filter { it.kind == BeatmapsetEventType.NOMINATE && it.userId != null }
                .sortedBy { it.id }
                .groupBy { it.beatmapset?.id }
                .filterKeys { it != null && beatmapService.findBeatmap(it) == null }
                .map { (id, events) -> id!! to nominatorsByGamemode(events) }
                .toMap()
        log.info { "Found ${nominations.size} nominations on osu! since $since, of ${missing.size} beatmaps that are not on the planner" }

        return missing
    }

    private fun nominatorsByGamemode(events: List<BeatmapsetEvent>): Map<Gamemode, List<String>> =
        events
            .flatMap { event ->
                event.comment
                    ?.modes
                    .orEmpty()
                    .mapNotNull { mode -> Gamemode.entries.find { it.name == mode } }
                    .map { it to event.userId.toString() }
            }.groupBy({ it.first }, { it.second })

    companion object {
        /** A year of nominations, with room to spare */
        const val MAX_PAGES = 2_000
        const val MAX_DAYS = 365
    }
}
