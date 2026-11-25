package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.profile.ProfileStatisticsPairInfo
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfileServiceTest {
    private val planner = Planner()

    private fun pairing(id: String = "5") = planner.profileService.getUserPairing(id)

    @Test
    fun `Should count the other nominators, sorted by name`() {
        planner.user("6", "Zed")
        planner.user("7", "Alice")
        BeatmapStatus.entries.forEach { planner.save(beatmap(it.name, mode(Gamemode.osu, "5", "6"), status = it)) }
        planner.save(beatmap("2", mode(Gamemode.osu, "7", "5"), mode(Gamemode.mania, "5", "6"), status = BeatmapStatus.Ranked))
        planner.save(beatmap("3", mode(Gamemode.osu, "7", "5"), status = BeatmapStatus.Graved))
        planner.save(beatmap("4", mode(Gamemode.osu, "7", "5"), status = BeatmapStatus.Graved))
        // Nothing to count
        planner.save(beatmap("5", mode(Gamemode.osu, "6", "7"), mode(Gamemode.taiko, "5", "0"), mode(Gamemode.mania, "0", "5")))
        planner.save(beatmap("6", mode(Gamemode.osu, "6", "7")))

        assertEquals(
            listOf(ProfileStatisticsPairInfo("Alice", 0, 1, 2), ProfileStatisticsPairInfo("Zed", 6, 2, 1)),
            pairing(),
        )
    }

    @Test
    fun `Should leave out unknown nominators`() {
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "unknown")))

        assertEquals(emptyList<ProfileStatisticsPairInfo>(), pairing())
        assertEquals(emptyList<ProfileStatisticsPairInfo>(), pairing("nobody"))
    }

    @Test
    fun `Should count being both nominators as pairing with yourself`() {
        planner.user("5", "Me")
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "5")))

        assertEquals(listOf(ProfileStatisticsPairInfo("Me", 1, 0, 0)), pairing())
    }

    @Test
    fun `SUSPECTED BUG - The pairing of the missing nominator counts every nominator with a missing one`() {
        planner.user("6", "Zed")
        planner.save(beatmap("1", mode(Gamemode.osu, "6", "0")))

        assertEquals(listOf(ProfileStatisticsPairInfo("Zed", 1, 0, 0)), pairing("0"))
    }

    private fun gamemode(vararg nominators: String) = BeatmapGamemode(Gamemode.osu, nominators.map { BeatmapNominator(it, false) }, false)

    @Test
    fun `SUSPECTED BUG - Pairing fails on a single nominator`() {
        planner.save(beatmap("1", gamemode("5")))

        assertThrows<IndexOutOfBoundsException> { pairing() }
    }

    @Test
    fun `SUSPECTED BUG - Pairing ignores a third nominator`() {
        planner.user("6", "Zed")
        planner.user("7", "Alice")
        planner.save(beatmap("1", gamemode("5", "6", "7")))
        planner.save(beatmap("2", gamemode("6", "7", "5")))

        assertEquals(listOf(ProfileStatisticsPairInfo("Zed", 1, 0, 0)), pairing())
    }
}
