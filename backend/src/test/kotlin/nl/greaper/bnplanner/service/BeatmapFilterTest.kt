package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapFilter
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The filters are verified by what they find */
class BeatmapFilterTest {
    private val planner = Planner()
    private val service = planner.beatmapService

    private fun save(vararg beatmaps: Beatmap) = beatmaps.forEach { planner.save(it) }

    private fun find(
        search: String? = null,
        nominators: Set<String>? = null,
        page: BeatmapPage = BeatmapPage.PENDING,
        gamemodes: Set<Gamemode>? = null,
    ): Set<String> {
        val filter = BeatmapFilter(page, search, nominators, gamemodes)
        val ids = service.findBeatmaps(filter, 1, PageLimit.FIFTY).map { it.osuId }.toSet()
        assertEquals(ids.size, service.countBeatmaps(filter))

        return ids
    }

    private fun saveEveryStatus() = BeatmapStatus.entries.forEach { save(beatmap(it.name, status = it)) }

    @Test
    fun `Should find every status of a page`() {
        saveEveryStatus()

        assertEquals(setOf("Qualified", "Nominated", "Disqualified", "Reset", "Pending", "Unfinished"), find())
        assertEquals(setOf("Ranked"), find(page = BeatmapPage.RANKED))
        assertEquals(setOf("Graved"), find(page = BeatmapPage.GRAVEYARD))
    }

    @Test
    fun `Should find the ids of beatmaps in progress with the requested statuses`() {
        saveEveryStatus()

        assertEquals(setOf("Qualified", "Reset"), service.findPendingIds(setOf(BeatmapStatus.Qualified, BeatmapStatus.Reset)))
        assertEquals(setOf("Qualified", "Nominated", "Disqualified", "Reset", "Pending", "Unfinished"), service.findPendingIds(emptySet()))
    }

    @Test
    fun `Should search on artist, title and mapper`() {
        save(
            beatmap("1", artist = "Camellia", title = "Ghost", mapper = "Someone"),
            beatmap("2", artist = "Someone", title = "camellia flower", mapper = "Mapper"),
            beatmap("3", artist = "xi", title = "Blue", mapper = "Camellia fan"),
        )

        assertEquals(setOf("1", "2", "3"), find(search = "cAMELLIA"))
        assertEquals(setOf("1", "2"), find(search = "someone"))
        assertEquals(setOf("3"), find(search = "blue"))
        assertEquals(setOf("1", "2", "3"), find(search = ""))
    }

    @Test
    fun `Should search on special characters literally`() {
        save(
            beatmap("1", title = "What? (feat. [a+b])"),
            beatmap("2", title = "What feat a+b"),
            beatmap("3", title = "C:\\path \$1.*^|{}"),
            beatmap("4", title = "どこ"),
            beatmap("5", title = "ÀÉ"),
            beatmap("6", title = "\\Ea.c"),
        )

        assertEquals(setOf("1"), find(search = "what? (feat. [A+B])"))
        assertEquals(setOf("3"), find(search = "c:\\PATH \$1.*^|{}"))
        assertEquals(setOf("3"), find(search = ".*"))
        assertEquals(setOf("4"), find(search = "こ"))
        assertEquals(setOf("5"), find(search = "ÀÉ"))
        // The end of a regex quote
        assertEquals(emptySet<String>(), find(search = "\\Ea.c\\Q"))
        assertEquals(setOf("6"), find(search = "\\Ea.c"))
    }

    @Test
    fun `Should find on nominators and gamemodes`() {
        save(
            beatmap("1", mode(Gamemode.osu, "5", "6")),
            beatmap("2", mode(Gamemode.taiko, "6", "7"), mode(Gamemode.osu)),
            beatmap("3", mode(Gamemode.mania, "0", "8")),
        )

        assertEquals(setOf("1"), find(nominators = setOf("5")))
        assertEquals(setOf("1", "2"), find(nominators = setOf("6")))
        assertEquals(setOf("1", "3"), find(nominators = setOf("5", "8")))
        assertEquals(setOf("2", "3"), find(nominators = setOf("0")))
        assertEquals(emptySet<String>(), find(nominators = setOf("9")))
        assertEquals(setOf("1", "2", "3"), find(nominators = emptySet(), gamemodes = emptySet()))
        assertEquals(setOf("1", "2"), find(gamemodes = setOf(Gamemode.osu)))
        assertEquals(setOf("2", "3"), find(gamemodes = setOf(Gamemode.taiko, Gamemode.mania)))
        assertEquals(setOf("2"), find(gamemodes = setOf(Gamemode.osu), nominators = setOf("7")))
        assertEquals(emptySet<String>(), find(gamemodes = setOf(Gamemode.fruits)))
    }

    @Test
    fun `Should combine every filter`() {
        val match = beatmap("1", mode(Gamemode.osu, "5", "0"), title = "unique", status = BeatmapStatus.Nominated)
        save(
            match,
            match.copy(osuId = "2", status = BeatmapStatus.Ranked),
            match.copy(osuId = "3", title = "other"),
            match.copy(osuId = "4", gamemodes = listOf(mode(Gamemode.osu, "6", "0"))),
            match.copy(osuId = "5", gamemodes = listOf(mode(Gamemode.mania, "5", "0"))),
        )

        assertEquals(setOf("1"), find(search = "UNIQ", nominators = setOf("5"), gamemodes = setOf(Gamemode.osu)))
    }

    @Test
    fun `Should find the beatmaps of a nominator on every page`() {
        save(
            beatmap("1", mode(Gamemode.osu, "5"), status = BeatmapStatus.Pending),
            beatmap("2", mode(Gamemode.osu, "0", "5"), status = BeatmapStatus.Ranked),
            beatmap("3", mode(Gamemode.osu, "6"), status = BeatmapStatus.Graved),
            beatmap("4", mode(Gamemode.osu), mode(Gamemode.mania, "5"), status = BeatmapStatus.Graved),
        )

        assertEquals(setOf("1", "2", "4"), service.findBeatmapsByNominator("5").map { it.osuId }.toSet())
    }

    @Test
    fun `Should sort and limit what is found`() {
        planner.user("10")
        (1..25).forEach { save(beatmap("$it", updated = UPDATED.minusSeconds(it.toLong()))) }

        fun table(
            number: Int,
            limit: PageLimit,
        ) = service
            .findBeatmaps(BeatmapFilter(BeatmapPage.PENDING), number, limit)
            .map { it.osuId.toInt() }

        assertEquals((21..25).toList(), table(3, PageLimit.TEN))
    }

    @Test
    fun `Should show beatmaps of unknown mappers with the name osu gave`() {
        planner.user("10")
        save(beatmap("1"), beatmap("2", mapperId = "unknown", mapper = "Gone"))

        val found = service.findBeatmaps(BeatmapFilter(BeatmapPage.PENDING), 1, PageLimit.FIFTY).associate { it.osuId to it.mapper }

        assertEquals(setOf("1", "2"), found.keys)
        assertEquals("User 10", found["1"]!!.username)
        assertEquals(User("unknown", "Gone", emptyList()), found["2"])
    }
}
