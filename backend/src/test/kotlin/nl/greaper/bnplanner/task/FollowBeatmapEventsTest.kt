package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.datasource.FollowedDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.FakeOsu.EVENT_TYPES
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.support.TestMongo
import nl.greaper.bnplanner.util.Backoff
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class FollowBeatmapEventsTest {
    private val planner = Planner()
    private val followed = FollowedDataSource(TestMongo.database)
    private val sleeps = mutableListOf<Long>()
    private var now = Instant.parse("2026-11-26T19:00:00Z")

    private fun follower() =
        FollowBeatmapEvents(
            followed,
            planner.osuService,
            planner.osuClient,
            planner.beatmapService,
            planner.beatmaps,
            planner.osuJobs,
            { sleeps += it },
            Backoff { now },
        )

    private val follower = follower()

    private fun eventCalls() = FakeOsu.calls().filter { it.startsWith("GET /api/v2/beatmapsets/events") }

    init {
        FakeOsu.token("own")
    }

    @Test
    fun `Should start at the newest event the first time, changing nothing`() {
        planner.save(beatmap("2527957", mode(Gamemode.mania)))
        FakeOsu.beatmapsetEvents(1, FakeOsu.sample("beatmapset-events.json"))

        assertEquals(0, follower.follow())

        assertEquals(8058989, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(
            Document("_id", "beatmapset-events").append("lastId", 8058989L),
            TestMongo.database
                .getCollection("followed")
                .find()
                .single(),
        )
        assertEquals(beatmap("2527957", mode(Gamemode.mania)), planner.stored("2527957"))
        assertEquals(listOf("POST /oauth/token", "GET /api/v2/beatmapsets/events?$EVENT_TYPES&page=1"), FakeOsu.calls())
        assertEquals("Bearer own", FakeOsu.requests.last().header("Authorization"))
    }

    @Test
    fun `Should not start without any events`() {
        FakeOsu.beatmapsetEvents(1, """{"events":[]}""")

        assertEquals(0, follower.follow())
        assertNull(followed.findLastId(FollowBeatmapEvents.NAME))
    }

    @Test
    fun `Should start at the newest event in any order, waiting a second between pages by default`() {
        val follower =
            FollowBeatmapEvents(followed, planner.osuService, planner.osuClient, planner.beatmapService, planner.beatmaps, planner.osuJobs)
        FakeOsu.beatmapsetEvents(1, page(event(5, "qualify", "1"), event(7, "qualify", "1"), event(6, "qualify", "1")))

        follower.follow()

        assertEquals(7, followed.findLastId(FollowBeatmapEvents.NAME))
    }

    @Test
    fun `Should apply the newer events oldest first, and continue there after a restart`() {
        planner.user("34587251", "Nominator", nominator(Gamemode.mania))
        planner.save(beatmap("2527957", mode(Gamemode.mania, "34587251")))
        planner.save(beatmap("2652052", mode(Gamemode.osu, "1", "2", true, true), status = BeatmapStatus.Qualified))
        planner.save(beatmap("2589812", mode(Gamemode.osu, "845733", "14800030", true, true), status = BeatmapStatus.Qualified))
        // Handled already
        planner.save(beatmap("1316949", mode(Gamemode.osu, "10449071", firstNominated = true), status = BeatmapStatus.Nominated))
        followed.saveLastId(FollowBeatmapEvents.NAME, 8057264)
        FakeOsu.beatmapsetEvents(1, FakeOsu.sample("beatmapset-events.json"))

        assertEquals(4, follower.follow())

        assertEquals(8058989, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(BeatmapStatus.Qualified, planner.stored("2527957").status)
        assertEquals(listOf(mode(Gamemode.mania, "34587251", firstNominated = true)), planner.stored("2527957").gamemodes)
        assertEquals(BeatmapStatus.Ranked, planner.stored("2652052").status)
        assertEquals(Instant.parse("2026-11-26T16:47:13Z"), planner.stored("2652052").dateRanked)
        assertEquals(BeatmapStatus.Disqualified, planner.stored("2589812").status)
        assertEquals(listOf(mode(Gamemode.osu, "845733", "14800030")), planner.stored("2589812").gamemodes)
        assertEquals(BeatmapStatus.Nominated, planner.stored("1316949").status)
        assertEquals(
            listOf("Disqualified", "Ranked", "Nominated", "Qualified"),
            planner.discord.sent.map {
                it.description
                    .substringAfter("status to ")
                    .substringBefore("*")
            },
        )

        assertEquals(0, follower().follow())
        assertEquals(4, planner.discord.sent.size)
        assertEquals(2, eventCalls().size)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `Should read older pages until the last event handled, once each`() {
        planner.save(beatmap("1"))
        planner.save(beatmap("2"))
        followed.saveLastId(FollowBeatmapEvents.NAME, 100)
        FakeOsu.beatmapsetEvents(1, page(event(103, "qualify", "1"), event(102, "nomination_reset", "2")))
        // Moved to the next page by a newer event
        FakeOsu.beatmapsetEvents(2, page(event(102, "nomination_reset", "2"), event(101, "disqualify", "2"), event(100, "qualify", "2")))

        assertEquals(3, follower.follow())

        assertEquals(BeatmapStatus.Qualified, planner.stored("1").status)
        assertEquals(BeatmapStatus.Reset, planner.stored("2").status)
        assertEquals(103, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun `Should sync the beatmaps in progress when too much happened in between`() {
        planner.save(beatmap("1"))
        planner.save(beatmap("2", status = BeatmapStatus.Ranked))
        planner.save(beatmap("3", status = BeatmapStatus.Graved))
        followed.saveLastId(FollowBeatmapEvents.NAME, 1)
        (1..FollowBeatmapEvents.MAX_PAGES).forEach { FakeOsu.beatmapsetEvents(it, page(event(1000L - it, "qualify", "4"))) }
        FakeOsu.beatmap("1")

        assertEquals(0, follower.follow())
        planner.osuJobs.finish()

        assertEquals(FollowBeatmapEvents.MAX_PAGES, eventCalls().size)
        assertEquals(List(FollowBeatmapEvents.MAX_PAGES - 1) { 1_000L }, sleeps)
        assertEquals(listOf("GET /api/v2/beatmapsets/1"), FakeOsu.calls().filter { it.startsWith("GET /api/v2/beatmapsets/1") })
        assertEquals(999, followed.findLastId(FollowBeatmapEvents.NAME))
    }

    @Test
    fun `Should still sync the beatmaps in progress when an event after the gap fails`() {
        planner.save(beatmap("1"))
        planner.save(beatmap("2"))
        followed.saveLastId(FollowBeatmapEvents.NAME, 1)
        (2..FollowBeatmapEvents.MAX_PAGES).forEach { FakeOsu.beatmapsetEvents(it, page(event(1000L - it, "qualify", "4"))) }
        FakeOsu.beatmapsetEvents(1, page(event(999, "nominate", "2", 30, "osu")))
        FakeOsu.respond("GET /api/v2/users/30?key=id", "{}", 500)
        FakeOsu.beatmap("1")

        assertEquals(0, follower.follow())
        planner.osuJobs.finish()

        // Already past the events that were not read
        assertEquals(998, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(listOf("GET /api/v2/beatmapsets/1"), FakeOsu.calls().filter { it.startsWith("GET /api/v2/beatmapsets/1") })
    }

    @Test
    fun `Should sync only after following the events, so those don't overwrite how the beatmaps are now`() {
        planner.save(beatmap("1"))
        followed.saveLastId(FollowBeatmapEvents.NAME, 1)
        (1..FollowBeatmapEvents.MAX_PAGES).forEach { FakeOsu.beatmapsetEvents(it, page(event(1000L - it, "qualify", "1"))) }
        // Loved by now
        FakeOsu.beatmap("1", ranked = 4)

        assertEquals(1, follower.follow())
        planner.osuJobs.finish()

        assertEquals(BeatmapStatus.Graved, planner.stored("1").status)
    }

    @Test
    fun `Should ask osu which gamemode is the main one after a hybrid is nominated`() {
        planner.user("20", "Nominator", nominator(Gamemode.osu))
        planner.save(beatmap("1", mode(Gamemode.osu), mode(Gamemode.taiko)))
        planner.save(beatmap("2", mode(Gamemode.osu), mode(Gamemode.taiko), main = listOf(Gamemode.osu)))
        planner.save(beatmap("3", mode(Gamemode.osu)))
        planner.save(beatmap("4", mode(Gamemode.osu), mode(Gamemode.taiko), main = listOf(Gamemode.osu, Gamemode.taiko)))
        followed.saveLastId(FollowBeatmapEvents.NAME, 1)
        FakeOsu.beatmapsetEvents(
            1,
            page(
                event(6, "qualify", "1"),
                event(5, "nominate", "4", 20, "osu"),
                event(4, "nominate", "3", 20, "osu"),
                event(3, "nominate", "2", 20, "osu"),
                event(2, "nominate", "1", 20, "osu"),
                event(1, "nominate", "1", 20, "osu"),
            ),
        )
        FakeOsu.beatmap("1")
        FakeOsu.beatmap("4")

        assertEquals(5, follower.follow())
        planner.osuJobs.finish()

        assertEquals(
            listOf("GET /api/v2/beatmapsets/1", "GET /api/v2/beatmapsets/4"),
            FakeOsu.calls().filter { it.matches(Regex("GET /api/v2/beatmapsets/\\d+")) },
        )
    }

    @Test
    fun `Should wait longer after each failure, and start over once it works`() {
        FakeOsu.respond("POST /oauth/token", "{}", 401)

        assertEquals(0, follower.follow())
        assertEquals(0, follower.follow())
        assertEquals(1, FakeOsu.calls().size)

        now = now.plusSeconds(60)
        follower.follow()
        now = now.plusSeconds(119)
        follower.follow()
        assertEquals(2, FakeOsu.calls().size)

        FakeOsu.token("own")
        FakeOsu.beatmapsetEvents(1, "{}", 500)
        now = now.plusSeconds(1)
        follower.follow()
        assertEquals(1, eventCalls().size)

        FakeOsu.beatmapsetEvents(1, page(event(5, "qualify", "1")))
        now = now.plusSeconds(240)
        follower.follow()
        assertEquals(5, followed.findLastId(FollowBeatmapEvents.NAME))

        // Waits a minute again
        FakeOsu.beatmapsetEvents(1, "", 200)
        follower.follow()
        now = now.plusSeconds(60)
        follower.follow()
        assertEquals(4, eventCalls().size)
    }

    @Test
    fun `Should stop at an event that fails, to handle it again later`() {
        planner.save(beatmap("1"))
        planner.save(beatmap("2"))
        followed.saveLastId(FollowBeatmapEvents.NAME, 10)
        FakeOsu.beatmapsetEvents(
            1,
            page(
                event(13, "qualify", "1"),
                event(12, "nominate", "2", 30, "osu"),
                event(11, "nominate", "1", 20, "osu"),
                event(10, "qualify", "2"),
            ),
        )
        planner.user("20", "Nominator", nominator(Gamemode.osu))
        FakeOsu.respond("GET /api/v2/users/30?key=id", "{}", 500)

        assertEquals(0, follower.follow())
        assertEquals(11, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(BeatmapStatus.Nominated, planner.stored("1").status)
        assertEquals(BeatmapStatus.Pending, planner.stored("2").status)

        FakeOsu.user("30", "New nominator")
        now = now.plusSeconds(60)

        assertEquals(2, follower.follow())
        assertEquals(13, followed.findLastId(FollowBeatmapEvents.NAME))
        assertEquals(listOf(mode(Gamemode.osu, "30", firstNominated = true)), planner.stored("2").gamemodes)
        assertEquals(BeatmapStatus.Qualified, planner.stored("1").status)
    }

    companion object {
        fun page(vararg events: String) = """{"events":[${events.joinToString(",")}],"reviewsConfig":{"max_blocks":100},"users":[]}"""

        fun event(
            id: Long,
            type: String,
            beatmap: String,
            user: Long? = null,
            vararg modes: String,
        ): String {
            val comment = if (modes.isEmpty()) "null" else """{"modes":[${modes.joinToString(",") { "\"$it\"" }}]}"""
            return """{"id":$id,"type":"$type","comment":$comment,"created_at":"2026-11-26T18:51:07Z",""" +
                """"user_id":$user,"beatmapset":{"id":$beatmap}}"""
        }
    }
}
