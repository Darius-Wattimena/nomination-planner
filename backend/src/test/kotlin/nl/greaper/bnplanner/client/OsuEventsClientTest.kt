package nl.greaper.bnplanner.client

import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.GroupEventType
import nl.greaper.bnplanner.model.osu.GroupHistory
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Reading the recorded answers of osu!, and what is left out of them */
class OsuEventsClientTest {
    private val client = Planner.osuClient()

    init {
        FakeOsu.reset()
    }

    @Test
    fun `Should read the beatmap events of osu`() {
        FakeOsu.beatmapsetEvents(2, FakeOsu.sample("beatmapset-events.json"))

        val events = client.findBeatmapsetEvents("own", 2).events

        assertEquals(
            listOf(8058989L, 8058988, 8058785, 8057494, 8057493, 8057492, 8057491, 8057264),
            events.map { it.id },
        )
        assertEquals(
            BeatmapsetEvent(
                8058988,
                "nominate",
                BeatmapsetEvent.Comment(listOf("mania")),
                Instant.parse("2026-11-26T18:51:07Z"),
                34587251,
                BeatmapsetEvent.Beatmapset("2527957"),
            ),
            events[1],
        )
        assertEquals(BeatmapsetEvent.Comment(null), events[6].comment)
        assertEquals(
            listOf("QUALIFY", "NOMINATE", "RANK", "NOMINATION_RESET_RECEIVED", "DISQUALIFY", "NOMINATION_RESET"),
            events.mapNotNull { it.kind?.name }.distinct(),
        )
        assertEquals("Bearer own", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should read what osu may leave out of beatmap events`() {
        FakeOsu.beatmapsetEvents(1, """{"events":[{"id":1,"type":"love","created_at":"2026-11-26T18:51:07Z"}]}""")
        assertEquals(
            listOf(BeatmapsetEvent(1, "love", null, Instant.parse("2026-11-26T18:51:07Z"), null, null)),
            client.findBeatmapsetEvents("own", 1).events,
        )
        assertEquals(
            null,
            client
                .findBeatmapsetEvents("own", 1)
                .events
                .single()
                .kind,
        )

        FakeOsu.beatmapsetEvents(1, "{}")
        assertEquals(emptyList<BeatmapsetEvent>(), client.findBeatmapsetEvents("own", 1).events)
        assertEquals(BeatmapsetEventType.NOMINATE, BeatmapsetEvent(1, "nominate", createdAt = Instant.EPOCH).kind)
    }

    @Test
    fun `Should read the group history of osu`() {
        FakeOsu.groupHistory("28", FakeOsu.sample("group-history-28.json"))

        val history = client.findGroupHistory("28", null)

        assertEquals("eyJpZCI6MzU3OH0", history.cursorString)
        assertEquals(
            GroupEvent(
                3710,
                "user_add_playmodes",
                "28",
                "37783133",
                "nai_",
                "Beatmap Nominators",
                listOf("mania"),
                Instant.parse("2026-11-22T13:48:20Z"),
            ),
            history.events.last(),
        )
        assertEquals(listOf(null, GroupEventType.USER_ADD, GroupEventType.USER_REMOVE), history.events.take(3).map { it.kind })
    }

    @Test
    fun `Should read what osu may leave out of the group history`() {
        FakeOsu.groupHistory("28", """{"events":[{"id":1,"type":"user_remove","group_id":28,"created_at":"2026-11-24T19:51:17Z"}]}""")
        assertEquals(
            GroupHistory(listOf(GroupEvent(1, "user_remove", "28", createdAt = Instant.parse("2026-11-24T19:51:17Z")))),
            client.findGroupHistory("28", null),
        )

        FakeOsu.groupHistory("28", "{}")
        assertEquals(GroupHistory(), client.findGroupHistory("28", null))
    }
}
