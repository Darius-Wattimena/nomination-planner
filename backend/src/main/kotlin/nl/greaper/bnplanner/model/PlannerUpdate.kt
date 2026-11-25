package nl.greaper.bnplanner.model

import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import java.time.Instant

/** Everything that is in progress, or only what changed since an earlier update */
data class PlannerUpdate(
    /** To ask what changed since this update, has no meaning to others */
    val token: String,
    /** Contains everything, so whatever is missing does not exist (anymore) */
    val isComplete: Boolean,
    val users: List<User>,
    val beatmaps: List<PlannerBeatmap>,
    /** Deleted, or not in progress anymore */
    val removed: List<String>,
)

/** Users are only mentioned by their id, so they don't have to be repeated for every beatmap */
data class PlannerBeatmap(
    val id: String,
    val artist: String,
    val title: String,
    val note: String?,
    val mapper: String,
    /** Only for a mapper that is not one of the users */
    val mapperName: String?,
    val status: BeatmapStatus,
    val modes: List<PlannerMode>,
    val main: List<Gamemode>?,
    val added: Instant,
    val updated: Instant,
)

data class PlannerMode(
    val mode: Gamemode,
    val by: List<PlannerNominator>,
)

data class PlannerNominator(
    val user: String,
    val done: Boolean?,
)
