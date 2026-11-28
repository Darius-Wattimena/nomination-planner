package nl.greaper.bnplanner.model.beatmap

import nl.greaper.bnplanner.model.Gamemode
import java.time.Instant

data class Beatmap(
    val osuId: String,
    val artist: String,
    val title: String,
    val note: String,
    val mapper: String,
    val mapperId: String,
    val status: BeatmapStatus,
    val gamemodes: List<BeatmapGamemode>,
    val dateAdded: Instant,
    val dateUpdated: Instant,
    val dateRanked: Instant?,
    /** The gamemodes that can be the main one, which is the only one that needs two nominations. Null when unknown. */
    val mainGamemodes: List<Gamemode>?,
)

data class BeatmapGamemode(
    val gamemode: Gamemode,
    /** Two or more, a missing nominator fills a place. Old ranked beatmaps can have more than two. */
    val nominators: List<BeatmapNominator>,
    /** osu! didn't tell who nominated this gamemode, so it was worked out from their other nominations */
    val guessed: Boolean = false,
)

data class BeatmapNominator(
    val nominatorId: String,
    val hasNominated: Boolean,
)
