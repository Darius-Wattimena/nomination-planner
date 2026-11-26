package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.Planner.Companion.nominator
import nl.greaper.bnplanner.support.RecordingDiscord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** How what happened on osu! changes the beatmaps on the planner */
class OsuEventTest {
    private val planner = Planner()

    init {
        planner.user("20", "Nominator 20", nominator(Gamemode.osu))
        planner.user("21", "Nominator 21", nominator(Gamemode.osu), nominator(Gamemode.taiko))
        planner.user("22", "Nominator 22", nominator(Gamemode.osu))
        planner.user("10", "Mapper")
    }

    private fun apply(event: BeatmapsetEvent) = planner.beatmapService.applyOsuEvent(TOKEN, event)

    private fun message(
        text: String,
        color: EmbedColor = EmbedColor.BLUE,
        footer: EmbedFooter = EmbedFooter("osu!"),
        gamemodes: List<Gamemode> = listOf(Gamemode.osu),
    ) = RecordingDiscord.Sent(
        "$text\n**[Artist 1 - Title 1](https://osu.ppy.sh/beatmapsets/1)**\nMapped by [Mapper](https://osu.ppy.sh/users/10)",
        color,
        true,
        gamemodes,
        EmbedThumbnail("https://b.ppy.sh/thumb/1l.jpg"),
        footer,
    )

    @Test
    fun `Should mark the nomination of a planned nominator`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21"))))

        val changed = apply(event("nominate", user = 21, modes = listOf("osu")))

        val expected =
            beatmap(
                gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", secondNominated = true)),
                status = BeatmapStatus.Nominated,
                updated = AT,
            )
        assertEquals(expected, changed)
        assertEquals(expected, planner.stored())
        assertEquals(
            message("💭 **Updated status to Nominated** [osu]", footer = EmbedFooter("Nominator 21", "https://a.ppy.sh/21")),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should take the place of a missing nominator before one who is planned`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "0"))))

        apply(event("nominate", user = 21, modes = listOf("osu")))

        assertEquals(listOf(mode(Gamemode.osu, "20", "21", secondNominated = true)), planner.stored().gamemodes)
    }

    @Test
    fun `Should take the place of a planned nominator who did not nominate`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "22", secondNominated = true))))

        apply(event("nominate", user = 21, modes = listOf("osu")))

        assertEquals(listOf(mode(Gamemode.osu, "21", "22", true, true)), planner.stored().gamemodes)
    }

    @Test
    fun `Should replace the first nominator when both nominated already, like a sync`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "22", true, true))))

        apply(event("nominate", user = 21, modes = listOf("osu")))

        assertEquals(listOf(mode(Gamemode.osu, "21", "22", true, true)), planner.stored().gamemodes)
    }

    @Test
    fun `Should add a gamemode that was nominated, leaving out one the planner does not know`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "22"))))

        apply(event("nominate", user = 21, modes = listOf("taiko", "quaver")))

        assertEquals(listOf(mode(Gamemode.osu, "20", "22"), mode(Gamemode.taiko, "21", firstNominated = true)), planner.stored().gamemodes)
        assertEquals(listOf(Gamemode.osu, Gamemode.taiko), planner.discord.single().gamemodes)
    }

    @Test
    fun `Should ask osu about a nominator the planner does not know yet`() {
        planner.save(beatmap())
        FakeOsu.user("30", "New nominator", """[{"id":28,"playmodes":["osu"]}]""")

        apply(event("nominate", user = 30, modes = listOf("osu")))

        assertEquals(listOf(mode(Gamemode.osu, "30", firstNominated = true)), planner.stored().gamemodes)
        assertEquals(listOf(nominator(Gamemode.osu)), planner.users.findUser("30")!!.gamemodes)
        assertEquals("Bearer $TOKEN", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should change nothing and fail, to try again later, when osu gives no clear answer about the nominator`() {
        planner.save(beatmap())
        FakeOsu.respond("GET /api/v2/users/30?key=id", "{}", 500)

        val error = assertThrows<IllegalStateException> { apply(event("nominate", user = 30, modes = listOf("osu"))) }

        assertEquals("Nominator 30 is unknown and osu! gave no clear answer (osuId: 1)", error.message)
        assertEquals(beatmap(), planner.stored())
    }

    @Test
    fun `Should only change the status of a nomination without gamemodes, and nothing without a nominator`() {
        planner.save(beatmap())

        assertNull(apply(event("nominate", modes = listOf("osu"))))
        assertEquals(BeatmapStatus.Nominated, apply(event("nominate", user = 21))!!.status)
        assertEquals(listOf(mode(Gamemode.osu)), planner.stored().gamemodes)
    }

    @Test
    fun `Should qualify`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true)), status = BeatmapStatus.Nominated))

        apply(event("qualify"))

        assertEquals(BeatmapStatus.Qualified, planner.stored().status)
        assertEquals(message("❤ **Updated status to Qualified**"), planner.discord.single())
    }

    @Test
    fun `Should rank, removing the gamemodes without nominations like a sync`() {
        planner.save(
            beatmap(
                gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true), mode(Gamemode.taiko)),
                status = BeatmapStatus.Qualified,
            ),
        )

        apply(event("rank"))

        val expected =
            beatmap(
                gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true)),
                status = BeatmapStatus.Ranked,
                updated = AT,
                ranked = AT,
            )
        assertEquals(expected, planner.stored())
        assertEquals(
            message("💖 **Updated status to Ranked**", EmbedColor.GREEN, gamemodes = listOf(Gamemode.osu)),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should keep the gamemodes when ranked without known nominations`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu), mode(Gamemode.taiko)), status = BeatmapStatus.Qualified))

        apply(event("rank"))

        assertEquals(listOf(mode(Gamemode.osu), mode(Gamemode.taiko)), planner.stored().gamemodes)
    }

    @Test
    fun `Should reset every nomination`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", firstNominated = true)), status = BeatmapStatus.Nominated))

        apply(event("nomination_reset", user = 3))

        assertEquals(
            beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21")), status = BeatmapStatus.Reset, updated = AT),
            planner.stored(),
        )
        assertEquals(message("🗯 **Updated status to Reset**", EmbedColor.ORANGE), planner.discord.single())
    }

    @Test
    fun `Should disqualify`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true)), status = BeatmapStatus.Qualified))

        apply(event("disqualify", user = 22))

        assertEquals(listOf(mode(Gamemode.osu, "20", "21")), planner.stored().gamemodes)
        assertEquals(BeatmapStatus.Disqualified, planner.stored().status)
        assertEquals(
            message("💔 **Updated status to Disqualified**", EmbedColor.ORANGE, EmbedFooter("Nominator 22", "https://a.ppy.sh/22")),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should take back the nomination of whoever osu tells, without a message`() {
        planner.save(beatmap(gamemodes = arrayOf(mode(Gamemode.osu, "20", "21", true, true)), status = BeatmapStatus.Qualified))

        apply(event("nomination_reset_received", user = 20))

        assertEquals(listOf(mode(Gamemode.osu, "20", "21", secondNominated = true)), planner.stored().gamemodes)
        assertEquals(BeatmapStatus.Qualified, planner.stored().status)
        assertEquals(emptyList<RecordingDiscord.Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should leave a ranked beatmap alone, like a sync`() {
        val ranked = planner.save(beatmap(status = BeatmapStatus.Ranked))

        assertNull(apply(event("disqualify", user = 22)))
        assertEquals(ranked, planner.stored())
    }

    @Test
    fun `Should change nothing when osu tells what is known already`() {
        val qualified = planner.save(beatmap(status = BeatmapStatus.Qualified))

        assertNull(apply(event("qualify")))
        assertEquals(qualified, planner.stored())
        assertEquals(UPDATED, planner.stored().dateUpdated)
        assertEquals(emptyList<RecordingDiscord.Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should ignore beatmaps that are not on the planner, deleted beatmap sets and other events`() {
        planner.save(beatmap())

        assertNull(apply(event("nominate", beatmap = "2", user = 30, modes = listOf("osu"))))
        assertNull(apply(event("nominate", beatmap = null, user = 30, modes = listOf("osu"))))
        assertNull(apply(event("love")))
        assertEquals(beatmap(), planner.stored())
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should work without Discord`() {
        val planner = Planner(withDiscord = false)
        planner.save(beatmap())

        assertEquals(BeatmapStatus.Qualified, planner.beatmapService.applyOsuEvent(TOKEN, event("qualify"))!!.status)
    }

    companion object {
        const val TOKEN = "own"
        val AT: Instant = Instant.parse("2026-11-26T18:51:07Z")

        fun event(
            type: String,
            beatmap: String? = "1",
            user: Long? = null,
            modes: List<String>? = null,
            id: Long = 8058988,
        ) = BeatmapsetEvent(id, type, modes?.let { BeatmapsetEvent.Comment(it) }, AT, user, beatmap?.let { BeatmapsetEvent.Beatmapset(it) })
    }
}
