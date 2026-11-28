package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.RoleChangeKind
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import java.time.ZoneOffset

/** What osu! told about a ranked beatmap with several gamemodes, of which it doesn't know who nominated which gamemode */
data class HistoryGuess(
    val beatmapId: String,
    /** The gamemodes of its difficulties */
    val modes: List<Gamemode>,
    /** The gamemode that needed the most nominations, or had the most difficulties */
    val main: Gamemode,
    /** The nominators osu! does know the gamemodes of */
    val certain: Map<String, List<Gamemode>>,
    /** The nominators osu! doesn't know the gamemodes of, in its order */
    val unknown: List<String>,
    val year: Int,
)

/**
 * Works out which gamemode someone nominated on an old ranked beatmap with several gamemodes, from what else they nominated around
 * that time. The rules of each era don't have to be known: each nominator lands in the gamemode they usually nominated, so two per
 * gamemode, one per gamemode, or all of them in the main gamemode follow from how they nominated elsewhere.
 */
object ModeGuesser {
    /** How much each kind of clue counts */
    private const val ROLE = 2.0
    private const val CERTAIN = 1.0

    // On a beatmap with several gamemodes, only some of the nominators nominated the main one
    private const val MAIN_OF_HYBRID = 0.25
    private const val NEXT_YEAR = 0.5
    private const val ANY_YEAR = 0.1

    /** Per person, per year, per gamemode, how much points to them nominating that gamemode */
    class Votes {
        private val votes = mutableMapOf<String, MutableMap<Int, MutableMap<Gamemode, Double>>>()

        fun add(
            userId: String,
            year: Int,
            gamemode: Gamemode,
            weight: Double,
        ) {
            val modes = votes.getOrPut(userId) { mutableMapOf() }.getOrPut(year) { mutableMapOf() }
            modes[gamemode] = (modes[gamemode] ?: 0.0) + weight
        }

        fun score(
            userId: String,
            year: Int,
            gamemode: Gamemode,
        ): Double {
            val years = votes[userId] ?: return 0.0
            val at = { y: Int -> years[y]?.get(gamemode) ?: 0.0 }

            return at(year) + NEXT_YEAR * (at(year - 1) + at(year + 1)) + ANY_YEAR * years.values.sumOf { it[gamemode] ?: 0.0 }
        }
    }

    /** @param beatmaps the ranked beatmaps, of which those with a guess only count for their main gamemode */
    fun findVotes(
        beatmaps: Collection<Beatmap>,
        guesses: Collection<HistoryGuess>,
        roles: Collection<RoleChange>,
    ): Votes {
        val votes = Votes()
        val guessed = guesses.map { it.beatmapId }.toSet()

        beatmaps
            .filter { it.status == BeatmapStatus.Ranked && it.osuId !in guessed }
            .forEach { beatmap ->
                val year = (beatmap.dateRanked ?: beatmap.dateUpdated).atZone(ZoneOffset.UTC).year
                beatmap.gamemodes
                    .filter { !it.guessed }
                    .forEach { mode ->
                        mode.nominators
                            .filter { it.nominatorId != MISSING_USER_ID }
                            .forEach { votes.add(it.nominatorId, year, mode.gamemode, CERTAIN) }
                    }
            }

        guesses.forEach { guess ->
            guess.certain.forEach { (userId, modes) -> modes.forEach { votes.add(userId, guess.year, it, CERTAIN) } }
            guess.unknown.forEach { votes.add(it, guess.year, guess.main, MAIN_OF_HYBRID) }
        }

        roles
            .filter { it.kind == RoleChangeKind.Added }
            .forEach { change -> change.gamemodes.forEach { votes.add(change.userId, change.at.year, it, ROLE) } }

        return votes
    }

    /** The gamemodes of the beatmap with who most likely nominated them, all of them marked as guessed */
    fun assign(
        guess: HistoryGuess,
        votes: Votes,
    ): List<BeatmapGamemode> {
        // The main gamemode first, so it wins a tie and takes whoever has no clues at all
        val candidates = listOf(guess.main) + (guess.modes - guess.main)
        val placed =
            guess.unknown.map { userId ->
                val best = candidates.maxBy { votes.score(userId, guess.year, it) }
                userId to listOf(best)
            }
        val byMode =
            (guess.certain.toList() + placed)
                .flatMap { (userId, modes) -> modes.map { it to userId } }
                .groupBy({ it.first }, { it.second })

        return Gamemode.entries
            .filter { it in byMode }
            .map { mode ->
                val nominators = byMode.getValue(mode).distinct().map { BeatmapNominator(it, true) }
                // Like the planner stores them, with a missing nominator in the second place of a gamemode one nominated
                val filled = nominators + List(maxOf(0, 2 - nominators.size)) { BeatmapNominator(MISSING_USER_ID, false) }
                BeatmapGamemode(mode, filled, guessed = true)
            }
    }
}
