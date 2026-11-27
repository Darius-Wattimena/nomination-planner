package nl.greaper.bnplanner.task

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.FollowedDataSource
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.service.UserService
import nl.greaper.bnplanner.util.Backoff
import nl.greaper.bnplanner.util.EventPages
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Follows who joins or leaves the BN, probationary BN and NAT on osu! (https://osu.ppy.sh/groups/history), and their gamemodes,
 * so the planner knows who can nominate without a sync of the users.
 */
@Component
@ConditionalOnProperty(name = ["osu.follow-groups.enabled"], havingValue = "true")
class FollowGroups(
    private val followed: FollowedDataSource,
    private val osuClient: OsuHttpClient,
    private val userService: UserService,
    private val sleeper: Sleeper = Sleeper.THREAD,
    private val backoff: Backoff = Backoff(),
) {
    private val log = KotlinLogging.logger { }
    private val pages = EventPages<GroupEvent>(sleeper, MAX_PAGES) { it.id }

    /** @return how many users were changed */
    @Scheduled(
        fixedDelayString = "\${osu.follow-groups.every-seconds}",
        initialDelayString = "\${osu.follow-groups.every-seconds}",
        timeUnit = TimeUnit.SECONDS,
    )
    fun follow(): Int {
        if (backoff.isWaiting()) {
            return 0
        }

        return try {
            GROUPS
                .mapIndexed { index, groupId ->
                    if (index > 0) {
                        sleeper.sleep(1_000)
                    }
                    followGroup(groupId)
                }.sum()
                .also { backoff.succeeded() }
        } catch (ex: Exception) {
            val seconds = backoff.failed()
            log.warn(ex) { "Could not follow the groups of osu!, trying again in $seconds seconds" }
            0
        }
    }

    private fun followGroup(groupId: String): Int {
        val name = "group-history-$groupId"
        val last = followed.findLastId(name)

        if (last == null) {
            // Who is in the groups now is known from user syncs
            val newest = osuClient.findGroupHistory(groupId, null).events.maxOfOrNull { it.id } ?: return 0
            followed.saveLastId(name, newest)
            log.info { "Following group $groupId of osu! from event $newest on" }
            return 0
        }

        var cursor: String? = null
        val found =
            pages.findNewer(last) { index ->
                if (index > 0 && cursor == null) {
                    null
                } else {
                    osuClient.findGroupHistory(groupId, cursor).also { cursor = it.cursorString }.events
                }
            }

        if (!found.complete) {
            log.warn { "Too many changes of group $groupId on osu! after event $last to read, a sync of the users shows the rest" }
        }

        return found.events.count { event ->
            val changed = userService.applyGroupEvent(event)
            followed.saveLastId(name, event.id)
            changed != null
        }
    }

    companion object {
        val GROUPS = listOf(MeGroup.BN, MeGroup.PBN, MeGroup.NAT)
        const val MAX_PAGES = 10
    }
}
