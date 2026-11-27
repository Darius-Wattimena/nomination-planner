package nl.greaper.bnplanner.model

import java.time.Instant

/** Every ranked beatmap with only what the statistics need, or what changed since an earlier update */
data class RankedUpdate(
    val token: String,
    val isComplete: Boolean,
    /** The names of whoever is mentioned by id */
    val names: Map<String, String>,
    val beatmaps: List<RankedBeatmap>,
    /** Deleted, or not ranked anymore */
    val removed: List<String>,
)

data class RankedBeatmap(
    val id: String,
    val title: String,
    val mapper: String,
    val ranked: Instant?,
    val modes: List<RankedMode>,
    /** When it was added to the planner, to know how long it took to get ranked */
    val added: Instant?,
)

data class RankedMode(
    val mode: Gamemode,
    val by: List<String>,
)
