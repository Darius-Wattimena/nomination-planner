package nl.greaper.bnplanner.model.osu

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** A page of https://osu.ppy.sh/beatmapsets/events, newest first */
data class BeatmapsetEvents(
    val events: List<BeatmapsetEvent> = emptyList(),
)

data class BeatmapsetEvent(
    val id: Long,
    val type: String,
    val comment: Comment? = null,
    @JsonProperty("created_at")
    val createdAt: Instant,
    /** Who did it, missing for what osu! does itself, like qualifying and ranking */
    @JsonProperty("user_id")
    val userId: Long? = null,
    /** Missing when the beatmap set is deleted */
    val beatmapset: Beatmapset? = null,
) {
    /** Old resets and disqualifications have only their reason as the comment, which says nothing of use here */
    data class Comment
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        constructor(
            /** The gamemodes of a nomination */
            @JsonProperty("modes")
            val modes: List<String>? = null,
        ) {
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            constructor(
                @Suppress("UNUSED_PARAMETER") reason: String,
            ) : this(null)
        }

    data class Beatmapset(
        val id: String,
    )

    val kind: BeatmapsetEventType?
        get() = BeatmapsetEventType.entries.find { it.osuName == type }
}

enum class BeatmapsetEventType(
    val osuName: String,
) {
    NOMINATE("nominate"),
    QUALIFY("qualify"),
    RANK("rank"),
    NOMINATION_RESET("nomination_reset"),

    /** One for each nominator who lost their nomination by a reset or disqualification */
    NOMINATION_RESET_RECEIVED("nomination_reset_received"),
    DISQUALIFY("disqualify"),
}
