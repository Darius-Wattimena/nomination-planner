package nl.greaper.bnplanner.util

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.osu.BeatmapSet

fun BeatmapGamemode.hasAnyNomination(): Boolean = this.nominators.any { nominator -> nominator.hasNominated }

fun BeatmapGamemode.hasAllNominations(): Boolean = this.nominators.all { nominator -> nominator.hasNominated }

/** The main gamemode needs two nominations and every other gamemode needs one */
fun Beatmap.hasEnoughNominations(): Boolean {
    val possibleMain = mainGamemodes?.let { main -> gamemodes.filter { it.gamemode in main } } ?: gamemodes

    return gamemodes.all { it.hasAnyNomination() } && (possibleMain.isEmpty() || possibleMain.any { it.hasAllNominations() })
}

/**
 * There are several as long as it isn't decided which gamemode is the main one, null when there is nothing to decide on.
 *
 * See https://osu.ppy.sh/wiki/en/Beatmap_ranking_procedure#main-mode
 */
fun BeatmapSet.findMainGamemodes(): List<Gamemode>? {
    // Not part of the documented api, so it may be missing
    val known =
        nominationsSummary
            ?.eligibleMainRulesets
            ?.mapNotNull { ruleset -> Gamemode.values().find { it.name == ruleset } }
            .orEmpty()

    if (known.isNotEmpty()) {
        return known
    }

    val difficulties = beatmaps.groupBy { it.mode }

    if (difficulties.size < 2) {
        return null
    }

    val most = difficulties.values.map { it.size }.max()
    val mostDifficulties = difficulties.filterValues { it.size == most }
    val byHost = mostDifficulties.mapValues { (_, beatmaps) -> beatmaps.count { it.user_id.toString() == user_id } }
    val mostByHost = byHost.values.max()

    return byHost.filterValues { it == mostByHost }.keys.toList()
}
