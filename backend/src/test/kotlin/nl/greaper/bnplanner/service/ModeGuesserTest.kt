package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.NominationGroup
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.RoleChangeKind
import nl.greaper.bnplanner.model.RoleChangeSource
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ModeGuesserTest {
    private fun ranked(
        id: String,
        year: Int,
        vararg gamemodes: BeatmapGamemode,
    ) = beatmap(id, *gamemodes, status = BeatmapStatus.Ranked, ranked = Instant.parse("$year-06-01T00:00:00Z"))

    /** Who usually nominated what, from single-mode beatmaps of the same year */
    private val history =
        listOf(
            ranked("101", 2014, mode(Gamemode.osu, "1", "2", true, true)),
            ranked("102", 2014, mode(Gamemode.taiko, "3", "4", true, true)),
            ranked("103", 2014, mode(Gamemode.osu, "1", "5", true, true)),
            ranked("104", 2014, mode(Gamemode.taiko, "3", "6", true, true)),
        )

    private fun guess(
        vararg unknown: String,
        main: Gamemode = Gamemode.osu,
        modes: List<Gamemode> = listOf(Gamemode.osu, Gamemode.taiko),
        year: Int = 2014,
    ) = HistoryGuess("1", modes, main, emptyMap(), unknown.toList(), year)

    private fun nominated(
        gamemode: Gamemode,
        vararg ids: String,
    ) = BeatmapGamemode(
        gamemode,
        ids.map { BeatmapNominator(it, true) } + List(maxOf(0, 2 - ids.size)) { BeatmapNominator("0", false) },
        guessed = true,
    )

    private fun assign(
        guess: HistoryGuess,
        roles: List<RoleChange> = emptyList(),
    ) = ModeGuesser.assign(guess, ModeGuesser.findVotes(history, listOf(guess), roles))

    @Test
    fun `Should place two nominators of each mode, like the first hybrid rules`() {
        assertEquals(listOf(nominated(Gamemode.osu, "1", "2"), nominated(Gamemode.taiko, "3", "4")), assign(guess("3", "1", "2", "4")))
    }

    @Test
    fun `Should place one nominator of the main mode and one of the other mode, like the later hybrid rules`() {
        assertEquals(listOf(nominated(Gamemode.osu, "5"), nominated(Gamemode.taiko, "6")), assign(guess("6", "5")))
    }

    @Test
    fun `Should keep three nominators of the main mode together, like old beatmaps`() {
        assertEquals(listOf(nominated(Gamemode.osu, "1", "2", "5")), assign(guess("1", "2", "5")))
    }

    @Test
    fun `Should give the main mode to someone without any clues`() {
        assertEquals(listOf(nominated(Gamemode.taiko, "3", "99")), assign(guess("3", "99", main = Gamemode.taiko)))
        assertEquals(listOf(nominated(Gamemode.osu, "99"), nominated(Gamemode.taiko, "3")), assign(guess("3", "99")))
    }

    @Test
    fun `Should prefer what someone nominated around that time`() {
        val switched = history + ranked("105", 2020, mode(Gamemode.osu, "3", "7", true, true))

        val votes = ModeGuesser.findVotes(switched, emptyList(), emptyList())

        assertEquals(listOf(nominated(Gamemode.taiko, "3")), ModeGuesser.assign(guess("3"), votes))
        assertEquals(listOf(nominated(Gamemode.osu, "3")), ModeGuesser.assign(guess("3", year = 2020), votes))
    }

    @Test
    fun `Should use the modes the role history knows`() {
        val role =
            RoleChange(
                "99",
                "Taiko BAT",
                NominationGroup.BAT,
                RoleChangeKind.Added,
                LocalDate.parse("2013-08-01"),
                listOf(Gamemode.taiko),
                RoleChangeSource.StaffLog,
            )

        assertEquals(listOf(nominated(Gamemode.taiko, "99")), assign(guess("99"), listOf(role)))
    }

    @Test
    fun `Should keep what osu knows`() {
        val known =
            HistoryGuess("1", listOf(Gamemode.osu, Gamemode.mania), Gamemode.osu, mapOf("3" to listOf(Gamemode.mania)), listOf("1"), 2014)

        assertEquals(listOf(nominated(Gamemode.osu, "1"), nominated(Gamemode.mania, "3")), assign(known))
    }
}
