package nl.greaper.bnplanner.model.beatmap

enum class BeatmapPage(
    /** The statuses of the beatmaps shown on this page */
    val statuses: Set<BeatmapStatus>,
) {
    PENDING(BeatmapStatus.entries.toSet() - BeatmapStatus.Ranked - BeatmapStatus.Graved),
    RANKED(setOf(BeatmapStatus.Ranked)),
    GRAVEYARD(setOf(BeatmapStatus.Graved)),
}
