package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.task.BackgroundSync
import nl.greaper.bnplanner.util.Sleeper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.util.concurrent.CountDownLatch

class OsuJobsTest {
    private val planner = Planner()

    @Test
    fun `Should sync one after the other with a pause in between`() {
        listOf("1", "2", "3").forEach {
            planner.save(beatmap(it))
            FakeOsu.beatmap(it, ranked = 3)
        }

        assertEquals(3, planner.osuJobs.syncBeatmaps(TOKEN, listOf("1", "2", "3", "unknown")) - 1)
        planner.osuJobs.finish()

        assertEquals(
            setOf(BeatmapStatus.Qualified),
            planner.beatmaps
                .list()
                .map { it.status }
                .toSet(),
        )
        assertEquals(4, planner.sleeps.size)
        planner.sleeps.forEach { assertEquals(true, it in 1_000L..1_999L, "$it") }
        assertEquals(0, planner.osuJobs.waiting)
    }

    @Test
    fun `Should not queue what is already waiting and continue after a failure`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service =
            mock<BeatmapService> {
                on { findBeatmap("1") } doThrow IllegalStateException("broken")
                on { findBeatmap("2") } doReturn beatmap("2")
            }
        val jobs =
            OsuJobs(
                service,
                Sleeper {
                    started.countDown()
                    release.await()
                },
            )

        assertEquals(2, jobs.syncBeatmaps(TOKEN, listOf("1", "2")))
        started.await()
        assertEquals(1, jobs.syncBeatmaps(TOKEN, listOf("1", "2", "1")))
        assertEquals(2, jobs.waiting)
        release.countDown()
        jobs.finish()

        verify(service, times(2)).findBeatmap("1")
        verify(service).syncBeatmap(TOKEN, beatmap("2"))
    }

    @Test
    fun `Should sync in the background on request`() {
        BeatmapStatus.entries.forEach { planner.save(beatmap(it.name, status = it)) }

        val info = planner.fixService.syncBeatmapsInBackground(TOKEN, BeatmapPage.PENDING, setOf(BeatmapStatus.Pending))
        assertEquals(1, info.totalSynced)
        assertEquals(0, info.duration)
        planner.osuJobs.finish()
        assertEquals(0, planner.fixService.findWaiting())
    }

    @Test
    fun `Should get a token of the planner itself and keep it`() {
        FakeOsu.respond("POST /oauth/token", """{"token_type":"Bearer","expires_in":86400,"access_token":"own"}""")

        assertEquals("own", planner.osuService.findClientToken())
        assertEquals("own", planner.osuService.findClientToken())
        assertEquals(1, FakeOsu.calls().size)
        val body = FakeOsu.requests.single().body
        listOf("\"grant_type\":\"client_credentials\"", "\"scope\":\"public\"", "\"client_id\":14229").forEach {
            assertEquals(true, body.contains(it), body)
        }
    }

    @Test
    fun `Should get a new token when it expires and nothing when osu refuses`() {
        FakeOsu.respond("POST /oauth/token", """{"token_type":"Bearer","expires_in":60,"access_token":"short"}""")
        assertEquals("short", planner.osuService.findClientToken())
        assertEquals("short", planner.osuService.findClientToken())
        assertEquals(2, FakeOsu.calls().size)

        FakeOsu.respond("POST /oauth/token", "{}", 401)
        assertNull(planner.osuService.findClientToken())
    }

    @Test
    fun `Should keep the beatmaps in progress up to date, the ones checked longest ago first`() {
        FakeOsu.respond("POST /oauth/token", """{"token_type":"Bearer","expires_in":86400,"access_token":"own"}""")
        BeatmapStatus.entries.forEach { planner.save(beatmap(it.name, status = it)) }
        val sync = BackgroundSync(planner.beatmaps, planner.osuService, planner.osuJobs, 4)

        val first = sync.sync()
        assertEquals(4, first.size)
        val second = sync.sync()
        assertEquals(setOf("Qualified", "Nominated", "Disqualified", "Reset", "Pending", "Unfinished"), (first + second).toSet())
        assertEquals(first.take(2), second.drop(2))
        planner.osuJobs.finish()
        assertEquals(true, FakeOsu.requests.filter { it.uri.startsWith("/api") }.all { it.header("Authorization") == "Bearer own" })
    }

    @Test
    fun `Should not sync in the background without a token`() {
        val jobs = mock<OsuJobs>()
        val osu = mock<OsuService> { on { findClientToken() } doReturn null }

        assertEquals(emptyList<String>(), BackgroundSync(mock<BeatmapDataSource>(), osu, jobs, 4).sync())
        verify(jobs, times(0)).syncBeatmaps(any(), any())
    }
}
