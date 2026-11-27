package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.RankedBeatmap
import nl.greaper.bnplanner.model.RankedMode
import nl.greaper.bnplanner.model.RankedUpdate
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import org.springframework.stereotype.Service

/** The statistics are added up by the website, so switching between modes and periods doesn't need the backend */
@Service
class RankedService(
    private val beatmapDataSource: BeatmapDataSource,
    private val userDataSource: UserDataSource,
    private val tokens: FeedTokens,
) {
    fun find(since: String?): RankedUpdate {
        val token = tokens.create()
        val versions = tokens.parse(since)
        val (beatmaps, removed) =
            if (versions == null) {
                beatmapDataSource.findAll { it.isRanked() } to emptyList()
            } else {
                // Read once, a beatmap can change or be deleted in between
                val changed =
                    beatmapDataSource.changes
                        .findSince(versions.beatmaps)
                        .associateWith { id -> beatmapDataSource.findById(id)?.takeIf { it.isRanked() } }
                changed.values.filterNotNull() to changed.filterValues { it == null }.keys.toList()
            }
        val changedUsers = versions?.let { userDataSource.changes.findSince(it.users) }.orEmpty()
        val mentioned = beatmaps.flatMap { findUserIds(it) }.toSet() + changedUsers

        return RankedUpdate(
            token = token,
            isComplete = versions == null,
            names = mentioned.mapNotNull { userDataSource.findUser(it) }.associate { it.osuId to it.username },
            beatmaps = beatmaps.map { toRankedBeatmap(it) },
            removed = removed,
        )
    }

    private fun Beatmap.isRanked(): Boolean = status == BeatmapStatus.Ranked

    private fun findUserIds(beatmap: Beatmap): List<String> =
        beatmap.gamemodes
            .flatMap { it.nominators }
            .map { it.nominatorId } + beatmap.mapperId

    private fun toRankedBeatmap(beatmap: Beatmap): RankedBeatmap =
        RankedBeatmap(
            id = beatmap.osuId,
            title = beatmap.title,
            mapper = beatmap.mapperId,
            ranked = beatmap.dateRanked,
            modes =
                beatmap.gamemodes.map { mode ->
                    RankedMode(mode.gamemode, mode.nominators.map { it.nominatorId }.filter { it != MISSING_USER_ID })
                },
            added = beatmap.dateAdded,
        )
}
