package nl.greaper.bnplanner.model.osu

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import nl.greaper.bnplanner.model.Gamemode
import java.time.Instant

/** A page of the search of osu!, the next one is found with the cursor */
data class BeatmapSetSearch(
    val beatmapsets: List<BeatmapSet> = emptyList(),
    /** Missing on the last page */
    @JsonProperty("cursor_string")
    val cursorString: String? = null,
)

data class BeatmapSet(
    val id: String,
    val title: String,
    val artist: String,
    val creator: String,
    val user_id: String,
    @JsonProperty("current_nominations")
    val currentNominations: List<CurrentNomination> = emptyList(),
    val ranked: Int,
    val ranked_date: Instant? = null,
    val last_updated: Instant,
    val beatmaps: List<Beatmap> = emptyList(),
    @JsonProperty("nominations_summary")
    val nominationsSummary: NominationsSummary? = null,
) {
    data class NominationsSummary(
        @JsonProperty("eligible_main_rulesets")
        val eligibleMainRulesets: List<String>? = null,
    )

    data class CurrentNomination(
        /** Missing on nominations from before osu! kept the gamemodes, about 2022 */
        val rulesets: List<String>? = null,
        val reset: Boolean,
        @JsonAlias("user_id")
        val userId: Long,
    )

    data class Beatmap(
        val total_length: Int,
        val user_id: Int,
        val mode: Gamemode,
        val version: String,
    )
}
