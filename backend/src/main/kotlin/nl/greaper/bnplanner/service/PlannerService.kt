package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.PlannerBeatmap
import nl.greaper.bnplanner.model.PlannerMode
import nl.greaper.bnplanner.model.PlannerNominator
import nl.greaper.bnplanner.model.PlannerUpdate
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import nl.greaper.bnplanner.util.isInProgress
import org.springframework.stereotype.Service

@Service
class PlannerService(
    private val beatmapDataSource: BeatmapDataSource,
    private val userDataSource: UserDataSource,
    private val tokens: FeedTokens,
) {
    fun find(since: String?): PlannerUpdate {
        val token = tokens.create()
        val versions = tokens.parse(since) ?: return findAll(token)

        // Null when it is no longer on the planner or no longer in progress
        val changed =
            beatmapDataSource.changes
                .findSince(versions.beatmaps, forPlanner = true)
                .associateWith { id -> beatmapDataSource.findById(id)?.takeIf { it.isInProgress() } }
        val beatmaps = changed.values.filterNotNull()
        val removed = changed.filterValues { it == null }.keys.toList()
        val changedUsers = userDataSource.changes.findSince(versions.users)

        return PlannerUpdate(
            token = token,
            isComplete = false,
            users = (changedUsers + findUserIds(beatmaps)).mapNotNull { userDataSource.findUser(it) },
            beatmaps = beatmaps.map { toPlannerBeatmap(it) },
            removed = removed,
        )
    }

    private fun findAll(token: String): PlannerUpdate {
        val beatmaps = beatmapDataSource.findAll { it.isInProgress() }
        val used = findUserIds(beatmaps)

        return PlannerUpdate(
            token = token,
            isComplete = true,
            users = userDataSource.allUsers().filter { it.osuId in used || it.canNominate() },
            beatmaps = beatmaps.map { toPlannerBeatmap(it) },
            removed = emptyList(),
        )
    }

    private fun User.canNominate(): Boolean = gamemodes.any { it.role != Role.Mapper }

    private fun findUserIds(beatmaps: List<Beatmap>): Set<String> =
        beatmaps
            .flatMap { beatmap -> beatmap.gamemodes.flatMap { it.nominators }.map { it.nominatorId } + beatmap.mapperId }
            .toSet() - MISSING_USER_ID

    private fun toPlannerBeatmap(beatmap: Beatmap): PlannerBeatmap =
        PlannerBeatmap(
            id = beatmap.osuId,
            artist = beatmap.artist,
            title = beatmap.title,
            note = beatmap.note.takeIf { it.isNotEmpty() },
            mapper = beatmap.mapperId,
            mapperName = beatmap.mapper.takeIf { userDataSource.findUser(beatmap.mapperId) == null },
            status = beatmap.status,
            modes = beatmap.gamemodes.map { PlannerMode(it.gamemode, findNominators(it)) },
            main = beatmap.mainGamemodes,
            added = beatmap.dateAdded,
            updated = beatmap.dateUpdated,
        )

    private fun findNominators(gamemode: BeatmapGamemode): List<PlannerNominator> =
        gamemode.nominators
            .dropLastWhile { it.nominatorId == MISSING_USER_ID }
            .map { PlannerNominator(it.nominatorId, true.takeIf { _ -> it.hasNominated }) }
}
