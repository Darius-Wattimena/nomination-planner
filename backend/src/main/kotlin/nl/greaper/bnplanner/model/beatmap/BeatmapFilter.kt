package nl.greaper.bnplanner.model.beatmap

import nl.greaper.bnplanner.model.Gamemode

/** What the website looks for on a page of beatmaps, a filter that is left out or empty finds everything */
data class BeatmapFilter(
    val page: BeatmapPage,
    /** Part of the artist, title or mapper name */
    val search: String? = null,
    /** Beatmaps any of them is a nominator of */
    val nominators: Set<String>? = null,
    /** Beatmaps with any of these gamemodes */
    val gamemodes: Set<Gamemode>? = null,
)
