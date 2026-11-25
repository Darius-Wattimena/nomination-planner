package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PlannerUpdate
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.async.DeferredResult

class LiveUpdatesTest {
    private val planner = Planner()
    private val tokens = FeedTokens(planner.beatmaps, planner.users)
    private val service = PlannerService(planner.beatmaps, planner.users, tokens)
    private val live = LiveUpdates(service, planner.beatmaps, planner.users, 25_000)

    private fun DeferredResult<PlannerUpdate>.await(): PlannerUpdate? {
        repeat(100) {
            if (hasResult()) return result as PlannerUpdate
            Thread.sleep(10)
        }
        return null
    }

    @Test
    fun `Should answer right away when there is something to tell`() {
        planner.save(beatmap("1"))

        assertTrue(live.wait(null).await()!!.isComplete)
        assertTrue(live.wait("unknown").await()!!.isComplete)
        val token = service.find(null).token
        planner.beatmaps.update(beatmap("1", note = "changed"))
        assertEquals(
            listOf("1"),
            live
                .wait(token)
                .await()!!
                .beatmaps
                .map { it.id },
        )
    }

    @Test
    fun `Should answer once something changed`() {
        planner.save(beatmap("1"))
        val token = service.find(null).token
        val first = live.wait(token)
        val second = live.wait(token)
        assertFalse(first.hasResult())

        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked))
        assertEquals(listOf("1"), first.await()!!.removed)
        assertEquals(listOf("1"), second.await()!!.removed)

        val users = live.wait(tokens.create())
        planner.user("5", "BN", nominator(Gamemode.osu))
        assertEquals(listOf("BN"), users.await()!!.users.map { it.username })
    }

    @Test
    fun `Should not answer when only something else changed`() {
        val token = service.find(null).token
        val waiting = live.wait(token)
        // Something not in progress, like a ranked beatmap
        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked))
        planner.beatmaps.deleteById("1")

        Thread.sleep(200)
        assertTrue(!waiting.hasResult() || (waiting.result as PlannerUpdate).removed == listOf("1"))
    }
}
