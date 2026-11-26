package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.profile.ProfileStatisticsPairInfo
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import org.springframework.stereotype.Service

@Service
class ProfileService(
    private val beatmapService: BeatmapService,
    private val userService: UserService,
) {
    private class Count {
        var pending = 0
        var ranked = 0
        var graved = 0

        fun add(status: BeatmapStatus) {
            when (status) {
                BeatmapStatus.Ranked -> ranked++
                BeatmapStatus.Graved -> graved++
                else -> pending++
            }
        }
    }

    /** Who a nominator nominated with, by the two nominators of each mode */
    fun getUserPairing(userId: String): List<ProfileStatisticsPairInfo> {
        if (userId == MISSING_USER_ID) {
            return emptyList()
        }

        val counts = mutableMapOf<String, Count>()
        beatmapService.findBeatmapsByNominator(userId).forEach { beatmap ->
            beatmap.gamemodes.forEach { gamemode ->
                // A mode has two nominators, only those two are a pair
                val pair = gamemode.nominators.take(2).map { it.nominatorId }
                val other =
                    when (userId) {
                        pair.first() -> pair.getOrNull(1)
                        pair.getOrNull(1) -> pair.first()
                        else -> null
                    }

                if (other != null && other != MISSING_USER_ID) {
                    counts.getOrPut(other) { Count() }.add(beatmap.status)
                }
            }
        }

        return counts
            .mapNotNull { (id, count) ->
                userService.findUserById(id)?.let { ProfileStatisticsPairInfo(id, it.username, count.pending, count.ranked, count.graved) }
            }.sortedBy { it.name }
    }
}
