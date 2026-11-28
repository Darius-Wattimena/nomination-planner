package nl.greaper.bnplanner.model

import com.fasterxml.jackson.annotation.JsonProperty

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

/**
 * Sent for every ranked beatmap of the history, so with names of one letter and times in seconds since 1970, which is a quarter
 * of the size of whole words and dates as text
 */
data class RankedBeatmap(
    @get:JsonProperty("i") val id: String,
    @get:JsonProperty("t") val title: String,
    @get:JsonProperty("m") val mapper: String,
    @get:JsonProperty("r") val ranked: Long?,
    @get:JsonProperty("n") val modes: List<RankedMode>,
    /** When it was added to the planner, to know how long it took to get ranked */
    @get:JsonProperty("a") val added: Long?,
)

data class RankedMode(
    @get:JsonProperty("g") val mode: Gamemode,
    @get:JsonProperty("b") val by: List<String>,
)
