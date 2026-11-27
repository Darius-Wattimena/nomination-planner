package nl.greaper.bnplanner.model.beatmap

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import java.time.Instant

/** What happened to a beatmap, so its history can be shown. Only kept from the release that started recording them. */
enum class BeatmapEventType {
    Added,
    NominatorJoined,
    NominatorLeft,
    NominatorReplaced,
    NoteChanged,

    /** Changed by an admin, or by an event of osu! */
    StatusChanged,
    ModeAdded,
    ModeRemoved,
    Synced,
}

data class BeatmapEvent(
    val beatmapId: String,
    val type: BeatmapEventType,
    val at: Instant,
    /** Who did it, on the planner or on osu!. Null for what osu! or the planner did by itself. */
    val userId: String? = null,
    val gamemodes: List<Gamemode> = emptyList(),
    /** Who joined or left, or who replaced someone */
    val nominatorId: String? = null,
    /** Who was replaced */
    val oldNominatorId: String? = null,
    /** The status afterwards, for a sync only when it changed */
    val status: BeatmapStatus? = null,
    /** The event of osu! that changed the status, null when changed on the planner */
    val osuEvent: BeatmapsetEventType? = null,
)

/** The same with the names of the users */
data class ExposedBeatmapEvent(
    val type: BeatmapEventType,
    val at: Instant,
    val user: User?,
    val gamemodes: List<Gamemode>,
    val nominator: User?,
    val oldNominator: User?,
    val status: BeatmapStatus?,
    val osuEvent: BeatmapsetEventType?,
)
