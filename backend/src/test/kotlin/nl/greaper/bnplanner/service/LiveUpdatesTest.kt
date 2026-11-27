package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PlannerUpdate
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LiveUpdatesTest {
    private val planner = Planner()
    private val tokens = FeedTokens(planner.beatmaps, planner.users)
    private val service = PlannerService(planner.beatmaps, planner.users, tokens)
    private val ranked = RankedService(planner.beatmaps, planner.users, tokens)

    // Long enough to never answer only because it waited long enough
    private val live = LiveUpdates(service, planner.beatmaps, planner.users, 60_000)

    @AfterEach
    fun stop() = live.stop()

    /** The answer, once there is one */
    private fun DeferredResult<PlannerUpdate>.answer(): CompletableFuture<PlannerUpdate> {
        val answer = CompletableFuture<PlannerUpdate>()
        setResultHandler { answer.complete(it as PlannerUpdate) }
        return answer
    }

    private fun CompletableFuture<PlannerUpdate>.await(): PlannerUpdate = get(10, TimeUnit.SECONDS)

    @Test
    fun `Should answer right away when there is something to tell`() {
        planner.save(beatmap("1"))

        assertTrue(live.wait(null).hasResult())
        assertTrue((live.wait(null).result as PlannerUpdate).isComplete)
        assertTrue((live.wait("unknown").result as PlannerUpdate).isComplete)
        val token = service.find(null).token
        planner.beatmaps.update(beatmap("1", note = "changed"))
        assertEquals(listOf("1"), (live.wait(token).result as PlannerUpdate).beatmaps.map { it.id })
    }

    @Test
    fun `Should answer once something changed`() {
        planner.save(beatmap("1"))
        val token = service.find(null).token
        val first = live.wait(token)
        val second = live.wait(token)
        val firstAnswer = first.answer()
        val secondAnswer = second.answer()
        assertFalse(first.hasResult())

        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked))
        assertEquals(listOf("1"), firstAnswer.await().removed)
        assertEquals(listOf("1"), secondAnswer.await().removed)
        // Built once for everyone waiting since the same change
        assertSame(first.result, second.result)

        val users = live.wait(tokens.create()).answer()
        planner.user("5", "BN", nominator(Gamemode.osu))
        assertEquals(listOf("BN"), users.await().users.map { it.username })
    }

    @Test
    fun `Should not answer when only something the planner does not show changed`() {
        planner.save(beatmap("1", status = BeatmapStatus.Ranked))
        planner.save(beatmap("2", status = BeatmapStatus.Graved))
        val token = service.find(null).token
        val waiting = live.wait(token)
        val answer = waiting.answer()

        // Neither was in progress before nor after
        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked, note = "edited"))
        planner.beatmaps.update(beatmap("2", status = BeatmapStatus.Ranked))
        planner.beatmaps.deleteById("2")
        planner.beatmaps.deleteById("unknown")
        assertFalse(waiting.hasResult())
        planner.save(beatmap("3"))

        // Only the change it does show
        val update = answer.await()
        assertEquals(listOf("3"), update.beatmaps.map { it.id })
        assertEquals(emptyList<String>(), update.removed)
        assertEquals(update, service.find(token).copy(token = update.token))
        // The ranked beatmaps do show those changes
        val rankedUpdate = ranked.find(token)
        assertEquals(listOf("1"), rankedUpdate.beatmaps.map { it.id })
        assertEquals(setOf("2", "unknown", "3"), rankedUpdate.removed.toSet())
    }

    @Test
    fun `Should still tell a beatmap is no longer in progress after it changed again`() {
        planner.save(beatmap("1"))
        val token = service.find(null).token
        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked))
        planner.beatmaps.update(beatmap("1", status = BeatmapStatus.Ranked, note = "edited"))

        assertEquals(listOf("1"), (live.wait(token).result as PlannerUpdate).removed)
    }

    @Test
    fun `Should leave waking up those waiting once stopped`() {
        val token = service.find(null).token
        live.stop()
        val waiting = live.wait(token)

        planner.save(beatmap("1"))
        assertFalse(waiting.hasResult())
    }
}
