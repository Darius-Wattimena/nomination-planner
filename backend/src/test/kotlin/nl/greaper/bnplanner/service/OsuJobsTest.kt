package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.task.BackgroundSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.util.concurrent.CountDownLatch

class OsuJobsTest {
    private val planner = Planner()

    private val own = """{"token_type":"Bearer","expires_in":86400,"access_token":"own"}"""

    @Test
    fun `Should sync one after the other with the token of the planner`() {
        FakeOsu.respond("POST /oauth/token", own)
        listOf("1", "2", "3").forEach {
            planner.save(beatmap(it))
            FakeOsu.beatmap(it, ranked = 3)
        }

        assertEquals(3, planner.osuJobs.syncBeatmaps(listOf("1", "2", "3", "unknown")) - 1)
        planner.osuJobs.finish()

        assertEquals(
            setOf(BeatmapStatus.Qualified),
            planner.beatmaps
                .list()
                .map { it.status }
                .toSet(),
        )
        assertEquals(0, planner.osuJobs.waiting)
        assertEquals(true, FakeOsu.requests.filter { it.uri.startsWith("/api") }.all { it.header("Authorization") == "Bearer own" })
    }

    @Test
    fun `Should not sync without a token of the planner`() {
        FakeOsu.respond("POST /oauth/token", "{}", 401)
        planner.save(beatmap("1"))
        planner.user("10", "Known")

        assertEquals(1, planner.osuJobs.syncBeatmaps(listOf("1")))
        assertEquals(1, planner.osuJobs.syncUsers(listOf("10")))
        planner.osuJobs.finish()

        assertEquals(listOf("POST /oauth/token", "POST /oauth/token"), FakeOsu.calls())
        assertEquals("Known", planner.users.findUser("10")!!.username)
        assertEquals(0, planner.osuJobs.waiting)
    }

    @Test
    fun `Should not queue what is already waiting and continue after a failure`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service =
            mock<BeatmapService> {
                on { findBeatmap("1") } doAnswer {
                    started.countDown()
                    release.await()
                    throw IllegalStateException("broken")
                }
                on { findBeatmap("2") } doReturn beatmap("2")
            }
        val osu = mock<OsuService> { on { findClientToken() } doReturn TOKEN }
        val jobs = OsuJobs(service, planner.userService, osu)

        assertEquals(2, jobs.syncBeatmaps(listOf("1", "2")))
        started.await()
        assertEquals(0, jobs.syncBeatmaps(listOf("1", "2", "1")))
        assertEquals(1, jobs.syncUsers(listOf("1")))
        assertEquals(0, jobs.syncUsers(listOf("1")))
        assertEquals(3, jobs.waiting)
        release.countDown()
        jobs.finish()
        assertEquals(0, jobs.waiting)

        // Done, so it can be queued again
        assertEquals(1, jobs.syncBeatmaps(listOf("1")))
        jobs.finish()

        verify(service, times(2)).findBeatmap("1")
        verify(service).syncBeatmap(TOKEN, beatmap("2"))
        assertEquals(0, jobs.waiting)
    }

    @Test
    fun `Should stop the job running and leave what is still queued`() {
        val started = CountDownLatch(1)
        var interrupted = false
        val service =
            mock<BeatmapService> {
                on { findBeatmap(any()) } doAnswer {
                    started.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (ex: InterruptedException) {
                        interrupted = true
                        // Like a call to osu! that gets interrupted, only logged as the planner is stopping
                        throw IllegalStateException("interrupted", ex)
                    }
                    null
                }
            }
        FakeOsu.respond("POST /oauth/token", own)
        val jobs = OsuJobs(service, planner.userService, planner.osuService)

        assertEquals(2, jobs.syncBeatmaps(listOf("1", "2")))
        started.await()
        jobs.stop()

        assertEquals(true, interrupted)
        verify(service).findBeatmap("1")
        // What was still queued is left for the restart, what is queued afterwards is not kept
        assertEquals(1, jobs.waiting)
        assertEquals(0, jobs.syncBeatmaps(listOf("3")))
        assertEquals(0, jobs.syncUsers(listOf("4", "5")))
        assertEquals(1, jobs.waiting)
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
        verify(jobs, times(0)).syncBeatmaps(any())
    }
}
