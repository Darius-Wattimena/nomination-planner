package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.regex.PatternSyntaxException

/** Queries a real MongoDB, so the filters are verified by what they find */
class BeatmapFilterTest {
    private val planner = Planner()
    private val service = planner.beatmapService

    private fun save(vararg beatmaps: Beatmap) = beatmaps.forEach { planner.save(it) }

    private fun find(
        search: String? = null,
        artist: String? = null,
        title: String? = null,
        mapper: String? = null,
        status: Set<BeatmapStatus> = emptySet(),
        nominators: Set<String> = emptySet(),
        page: BeatmapPage = BeatmapPage.PENDING,
        gamemodes: Set<Gamemode> = emptySet(),
        missingNominator: Set<Gamemode> = emptySet(),
    ): Set<String> {
        val ids = service.findBeatmapsIds(search, artist, title, mapper, status, nominators, page, gamemodes, missingNominator)
        val count = service.countBeatmaps(search, artist, title, mapper, status, nominators, page, gamemodes, missingNominator)
        assertEquals(ids.size, count)

        planner.user("10")
        val range =
            service.findBeatmaps("", search, artist, title, mapper, status, nominators, page, 0, 2000, gamemodes, missingNominator)
        assertEquals(ids, range.map { it.osuId }.toSet())
        val table =
            service.findBeatmaps(
                "",
                search,
                artist,
                title,
                mapper,
                status,
                nominators,
                page,
                1,
                PageLimit.FIFTY,
                gamemodes,
                missingNominator,
            )
        assertEquals(ids, table.map { it.osuId }.toSet())

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
    fun `Should find the requested statuses of a page`() {
        saveEveryStatus()

        assertEquals(setOf("Qualified", "Reset"), find(status = setOf(BeatmapStatus.Qualified, BeatmapStatus.Reset)))
        assertEquals(setOf("Ranked"), find(status = setOf(BeatmapStatus.Ranked), page = BeatmapPage.RANKED))
        assertEquals(setOf("Graved"), find(status = setOf(BeatmapStatus.Graved), page = BeatmapPage.GRAVEYARD))
    }

    @Test
    fun `Should find the whole page when none of the requested statuses are part of it`() {
        saveEveryStatus()

        assertEquals(
            setOf("Qualified", "Nominated", "Disqualified", "Reset", "Pending", "Unfinished"),
            find(status = setOf(BeatmapStatus.Ranked, BeatmapStatus.Graved)),
        )
        assertEquals(setOf("Ranked"), find(status = setOf(BeatmapStatus.Pending, BeatmapStatus.Graved), page = BeatmapPage.RANKED))
        assertEquals(setOf("Graved"), find(status = setOf(BeatmapStatus.Pending, BeatmapStatus.Ranked), page = BeatmapPage.GRAVEYARD))
    }

    @Test
    fun `SUSPECTED BUG - Statuses of another page are found when combined with a status of the page`() {
        saveEveryStatus()

        assertEquals(setOf("Pending", "Ranked"), find(status = setOf(BeatmapStatus.Pending, BeatmapStatus.Ranked)))
        assertEquals(
            setOf("Pending", "Ranked"),
            find(status = setOf(BeatmapStatus.Pending, BeatmapStatus.Ranked), page = BeatmapPage.RANKED),
        )
        assertEquals(
            setOf("Graved", "Ranked"),
            find(status = setOf(BeatmapStatus.Graved, BeatmapStatus.Ranked), page = BeatmapPage.GRAVEYARD),
        )
    }

    @Test
    fun `Should find on artist, title and mapper`() {
        save(
            beatmap("1", artist = "Camellia", title = "Ghost", mapper = "Someone"),
            beatmap("2", artist = "Someone", title = "camellia flower", mapper = "Mapper"),
            beatmap("3", artist = "xi", title = "Blue", mapper = "Camellia fan"),
        )

        assertEquals(setOf("1"), find(artist = "CAMELL"))
        assertEquals(setOf("2"), find(title = "Camellia"))
        assertEquals(setOf("3"), find(mapper = "camellia"))
        assertEquals(setOf("1", "2", "3"), find(search = "cAMELLIA"))
        assertEquals(setOf("1", "2"), find(search = "someone"))
        assertEquals(setOf("1"), find(artist = "a", title = "ghost", mapper = "one", search = "ghost"))
        assertEquals(emptySet<String>(), find(artist = "Camellia", title = "Blue"))
        assertEquals(setOf("1", "2", "3"), find(artist = "", search = ""))
    }

    @Test
    fun `Should search on special characters literally`() {
        save(
            beatmap("1", title = "What? (feat. [a+b])"),
            beatmap("2", title = "What feat a+b"),
            beatmap("3", title = "C:\\path \$1.*^|{}"),
            beatmap("4", title = "どこ"),
            beatmap("5", title = "ÀÉ"),
        )

        assertEquals(setOf("1"), find(title = "what? (feat. [A+B])"))
        assertEquals(setOf("3"), find(search = "c:\\PATH \$1.*^|{}"))
        assertEquals(setOf("3"), find(title = ".*"))
        assertEquals(setOf("4"), find(title = "こ"))
        assertEquals(setOf("5"), find(title = "ÀÉ"))
    }

    @Test
    fun `SUSPECTED BUG - Text containing the end of a regex quote is used as a regex`() {
        save(beatmap("1", title = "abc"))

        assertEquals(setOf("1"), find(title = "\\Ea.c\\Q"))
        assertThrows<PatternSyntaxException> { find(title = "\\Ea.c") }
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
        assertEquals(setOf("1", "2"), find(gamemodes = setOf(Gamemode.osu)))
        assertEquals(setOf("2", "3"), find(gamemodes = setOf(Gamemode.taiko, Gamemode.mania)))
        assertEquals(setOf("2"), find(gamemodes = setOf(Gamemode.osu), nominators = setOf("7")))
        assertEquals(emptySet<String>(), find(gamemodes = setOf(Gamemode.fruits)))
    }

    @Test
    fun `Should find on missing nominators`() {
        save(
            beatmap("1", mode(Gamemode.osu, "5", "6")),
            beatmap("2", mode(Gamemode.osu, "5", "0")),
            beatmap("3", mode(Gamemode.mania, "0", "8")),
            beatmap("4", mode(Gamemode.taiko, "0", "0")),
        )

        assertEquals(setOf("2"), find(missingNominator = setOf(Gamemode.osu)))
        assertEquals(setOf("2", "3"), find(missingNominator = setOf(Gamemode.osu, Gamemode.mania)))
        assertEquals(setOf("4"), find(missingNominator = setOf(Gamemode.taiko)))
        assertEquals(emptySet<String>(), find(missingNominator = setOf(Gamemode.fruits)))
    }

    @Test
    fun `SUSPECTED BUG - A missing nominator of another gamemode is found`() {
        save(beatmap("1", mode(Gamemode.osu, "5", "6"), mode(Gamemode.taiko, "7", "0")))

        assertEquals(setOf("1"), find(missingNominator = setOf(Gamemode.osu)))
    }

    @Test
    fun `Should combine every filter`() {
        val match = beatmap("1", mode(Gamemode.osu, "5", "0"), artist = "a", title = "t", mapper = "m", status = BeatmapStatus.Nominated)
        save(
            match,
            match.copy(osuId = "2", status = BeatmapStatus.Pending),
            match.copy(osuId = "3", artist = "other"),
            match.copy(osuId = "4", gamemodes = listOf(mode(Gamemode.osu, "6", "0"))),
        )

        assertEquals(
            setOf("1"),
            find(
                search = "T",
                artist = "A",
                title = "T",
                mapper = "M",
                status = setOf(BeatmapStatus.Nominated),
                nominators = setOf("5"),
                gamemodes = setOf(Gamemode.osu),
                missingNominator = setOf(Gamemode.osu),
            ),
        )
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
    fun `Should ignore the status when there is no page`() {
        saveEveryStatus()

        val filter = service.setupFilter(status = setOf(BeatmapStatus.Pending), page = null)
        assertEquals(BeatmapStatus.entries.size, planner.beatmaps.count(filter))
    }

    @Test
    fun `Should sort and limit what is found`() {
        planner.user("10")
        (1..25).forEach { save(beatmap("$it", updated = UPDATED.minusSeconds(it.toLong()))) }

        fun ids(
            from: Int,
            to: Int,
        ) = service
            .findBeatmaps("", null, null, null, null, emptySet(), emptySet(), BeatmapPage.PENDING, from, to, emptySet(), emptySet())
            .map { it.osuId.toInt() }

        fun table(
            number: Int,
            limit: PageLimit,
        ) = service
            .findBeatmaps("", null, null, null, null, emptySet(), emptySet(), BeatmapPage.PENDING, number, limit, emptySet(), emptySet())
            .map { it.osuId.toInt() }

        assertEquals((6..10).toList(), ids(5, 10))
        assertEquals((21..25).toList(), table(3, PageLimit.TEN))
    }

    @Test
    fun `Should leave out beatmaps of unknown mappers, while still counting them`() {
        planner.user("10")
        save(beatmap("1"), beatmap("2", mapperId = "unknown"))

        fun found(to: Int) =
            service
                .findBeatmaps("", null, null, null, null, emptySet(), emptySet(), BeatmapPage.PENDING, 0, to, emptySet(), emptySet())
                .map { it.osuId }

        assertEquals(listOf("1"), found(2000))
        assertEquals(
            2,
            service.countBeatmaps(null, null, null, null, emptySet(), emptySet(), BeatmapPage.PENDING, emptySet(), emptySet()),
        )
    }
}
