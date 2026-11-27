package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapEvent
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.service.OsuEventTest.Companion.AT
import nl.greaper.bnplanner.service.OsuEventTest.Companion.event
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.LOGIN
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.assertRecent
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** What is kept of what happened to a beatmap */
class BeatmapHistoryTest {
    private val planner = Planner()
    private val service = planner.beatmapService
    private val editor = planner.user("99", "Editor")
    private val rory = planner.user("20", "Rory", nominator(Gamemode.osu), nominator(Gamemode.taiko))

    init {
        planner.user("10", "Mapper")
        planner.user("21", "Other", nominator(Gamemode.osu))
    }

    /** Oldest first, which is easier to read in a test */
    private fun history(id: String = "1") = planner.events.findNewest(id, 100).reversed()

    private fun types(id: String = "1") = history(id).map { it.type }

    @Test
    fun `Should keep every field of an event`() {
        val full =
            BeatmapEvent(
                "1",
                BeatmapEventType.StatusChanged,
                AT,
                "20",
                listOf(Gamemode.osu, Gamemode.mania),
                "21",
                "22",
                BeatmapStatus.Qualified,
                BeatmapsetEventType.QUALIFY,
            )
        val bare = BeatmapEvent("1", BeatmapEventType.Synced, AT.plusSeconds(1))
        planner.events.insert(full)
        planner.events.insert(bare)
        planner.events.insert(bare.copy(beatmapId = "2"))

        assertEquals(listOf(bare, full), planner.events.findNewest("1", 10))
        assertEquals(listOf(bare), planner.events.findNewest("1", 1))
    }

    @Test
    fun `Should find the beatmaps that reached a status since a moment`() {
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.StatusChanged, AT, status = BeatmapStatus.Qualified))
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.Synced, AT.plusSeconds(5), status = BeatmapStatus.Qualified))
        planner.events.insert(BeatmapEvent("2", BeatmapEventType.StatusChanged, AT.minusSeconds(1), status = BeatmapStatus.Qualified))
        planner.events.insert(BeatmapEvent("3", BeatmapEventType.StatusChanged, AT, status = BeatmapStatus.Ranked))

        assertEquals(setOf("1"), planner.events.findBeatmapsReaching(BeatmapStatus.Qualified, AT))
    }

    @Test
    fun `Should show the history with the names of the users, newest first`() {
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.NominatorReplaced, AT, "99", listOf(Gamemode.osu), "20", "77"))
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.StatusChanged, AT, status = BeatmapStatus.Ranked))

        assertEquals(
            listOf(
                ExposedBeatmapEvent(BeatmapEventType.StatusChanged, AT, null, emptyList(), null, null, BeatmapStatus.Ranked, null),
                ExposedBeatmapEvent(
                    BeatmapEventType.NominatorReplaced,
                    AT,
                    editor,
                    listOf(Gamemode.osu),
                    rory,
                    User("77", "Unknown", emptyList()),
                    null,
                    null,
                ),
            ),
            service.findEvents("1"),
        )
    }

    @Test
    fun `Should show at most the newest 50 events`() {
        (1..55L).forEach { planner.events.insert(BeatmapEvent("1", BeatmapEventType.NoteChanged, AT.plusSeconds(it), "99")) }

        val shown = service.findEvents("1")
        assertEquals(50, shown.size)
        assertEquals(AT.plusSeconds(55), shown.first().at)
    }

    @Test
    fun `Should keep who added a beatmap`() {
        FakeOsu.beatmap("1")
        service.addBeatmap(LOGIN, NewBeatmap("1", listOf(Gamemode.taiko, Gamemode.osu)))

        val added = history().single()
        assertEquals(
            BeatmapEvent("1", BeatmapEventType.Added, planner.stored().dateAdded, "99", listOf(Gamemode.taiko, Gamemode.osu)),
            added,
        )
    }

    @Test
    fun `Should keep who changed the note or the status, only when it changed`() {
        planner.save(beatmap())

        service.updateBeatmapNote(LOGIN, "1", "Check the hitsounds")
        service.updateBeatmapNote(LOGIN, "1", "Check the hitsounds")
        service.updateBeatmapStatus(LOGIN, "1", BeatmapStatus.Graved)
        service.updateBeatmapStatus(LOGIN, "1", BeatmapStatus.Graved)

        val (note, status) = history()
        assertEquals(BeatmapEvent("1", BeatmapEventType.NoteChanged, note.at, "99"), note)
        assertEquals(BeatmapEvent("1", BeatmapEventType.StatusChanged, status.at, "99", status = BeatmapStatus.Graved), status)
        assertRecent(note.at)
    }

    @Test
    fun `Should keep who joined, left or replaced a nominator`() {
        planner.save(beatmap())

        service.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "0", "20")
        service.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "20", "21")
        service.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "21", "0")

        val (joined, replaced, left) = history()
        val at = planner.stored().dateUpdated
        assertEquals(BeatmapEvent("1", BeatmapEventType.NominatorJoined, joined.at, "99", listOf(Gamemode.osu), "20"), joined)
        assertEquals(BeatmapEvent("1", BeatmapEventType.NominatorReplaced, replaced.at, "99", listOf(Gamemode.osu), "21", "20"), replaced)
        assertEquals(BeatmapEvent("1", BeatmapEventType.NominatorLeft, at, "99", listOf(Gamemode.osu), "21"), left)
    }

    @Test
    fun `Should keep a mode that was added or removed along with its nominator`() {
        planner.save(beatmap())

        service.updateBeatmapNominator(LOGIN, "1", Gamemode.taiko, "0", "20")
        service.updateBeatmapNominator(LOGIN, "1", Gamemode.taiko, "20", "0")

        assertEquals(
            listOf(
                BeatmapEventType.ModeAdded,
                BeatmapEventType.NominatorJoined,
                BeatmapEventType.NominatorLeft,
                BeatmapEventType.ModeRemoved,
            ),
            types(),
        )
        assertEquals(listOf(Gamemode.taiko), history().flatMap { it.gamemodes }.distinct())
    }

    @Test
    fun `Should keep nothing when an open spot stays open`() {
        planner.save(beatmap())

        service.updateBeatmapNominator(LOGIN, "1", Gamemode.osu, "0", "0")

        assertEquals(emptyList<BeatmapEvent>(), history())
    }

    @Test
    fun `Should keep what happened on osu, with who did it where known`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true))))

        service.applyOsuEvent(TOKEN, event("qualify"))
        service.applyOsuEvent(TOKEN, event("disqualify", user = 22))
        service.applyOsuEvent(TOKEN, event("nomination_reset_received", user = 20))
        service.applyOsuEvent(TOKEN, event("nominate", user = 20, modes = listOf("osu")))

        assertEquals(
            listOf(
                BeatmapEvent(
                    "1",
                    BeatmapEventType.StatusChanged,
                    AT,
                    status = BeatmapStatus.Qualified,
                    osuEvent = BeatmapsetEventType.QUALIFY,
                ),
                BeatmapEvent(
                    "1",
                    BeatmapEventType.StatusChanged,
                    AT,
                    "22",
                    status = BeatmapStatus.Disqualified,
                    osuEvent = BeatmapsetEventType.DISQUALIFY,
                ),
                BeatmapEvent(
                    "1",
                    BeatmapEventType.StatusChanged,
                    AT,
                    "20",
                    listOf(Gamemode.osu),
                    status = BeatmapStatus.Nominated,
                    osuEvent = BeatmapsetEventType.NOMINATE,
                ),
            ),
            history(),
        )
    }

    @Test
    fun `Should keep who synced a beatmap, with the status when it changed`() {
        planner.save(beatmap())
        FakeOsu.beatmap("1")
        service.syncBeatmap(LOGIN, "1")
        FakeOsu.beatmap("1", ranked = 3)
        service.syncBeatmap(LOGIN, "1")

        val (unchanged, qualified) = history()
        assertEquals(BeatmapEvent("1", BeatmapEventType.Synced, unchanged.at, "99"), unchanged)
        assertEquals(BeatmapEvent("1", BeatmapEventType.Synced, qualified.at, "99", status = BeatmapStatus.Qualified), qualified)
        assertRecent(qualified.at)
    }

    @Test
    fun `Should only keep a sync in the background when it changed something`() {
        FakeOsu.beatmap("1")
        val synced = beatmap(updated = Instant.parse("2024-01-01T00:00:00Z"))
        planner.save(synced)
        service.syncBeatmap(TOKEN, planner.stored())
        service.syncBeatmap(TOKEN, planner.stored())

        assertEquals(listOf(BeatmapEvent("1", BeatmapEventType.Synced, history().single().at)), history())
    }
}
