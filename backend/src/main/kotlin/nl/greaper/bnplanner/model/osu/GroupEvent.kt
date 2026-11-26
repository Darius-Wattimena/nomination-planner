package nl.greaper.bnplanner.model.osu

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** A page of https://osu.ppy.sh/groups/history, newest first */
data class GroupHistory(
    val events: List<GroupEvent> = emptyList(),
    /** Gives the next (older) page, missing on the last one */
    @JsonProperty("cursor_string")
    val cursorString: String? = null,
)

data class GroupEvent(
    val id: Long,
    val type: String,
    @JsonProperty("group_id")
    val groupId: String,
    @JsonProperty("user_id")
    val userId: String? = null,
    @JsonProperty("user_name")
    val userName: String? = null,
    @JsonProperty("group_name")
    val groupName: String? = null,
    val playmodes: List<String>? = null,
    @JsonProperty("created_at")
    val createdAt: Instant,
) {
    val kind: GroupEventType?
        get() = GroupEventType.entries.find { it.osuName == type }
}

enum class GroupEventType(
    val osuName: String,
) {
    USER_ADD("user_add"),
    USER_REMOVE("user_remove"),
    USER_ADD_PLAYMODES("user_add_playmodes"),
    USER_REMOVE_PLAYMODES("user_remove_playmodes"),
}
