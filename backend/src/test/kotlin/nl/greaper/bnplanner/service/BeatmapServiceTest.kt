package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmap
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapNominator
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.FakeOsu
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.ADDED
import nl.greaper.bnplanner.support.Planner.Companion.TOKEN
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.assertRecent
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.RecordingDiscord.Sent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import java.time.Instant

class BeatmapServiceTest {
    private val planner = Planner()
    private val service = planner.beatmapService
    private val editor = planner.user("99", "Editor")
    private val mapper = planner.user("10", "Mapper")
    private val none = User("0", "None", emptyList())

    private val footer =
        "\n**[Artist 1 - Title 1](https://osu.ppy.sh/beatmapsets/1)**\nMapped by [Mapper](https://osu.ppy.sh/users/10)"
    private val boldFooter =
        "\n[Artist 1 - Title 1](https://osu.ppy.sh/beatmapsets/1)**\nMapped by [Mapper](https://osu.ppy.sh/users/10)"

    private fun update(
        description: String,
        color: EmbedColor,
        confidential: Boolean,
        gamemodes: List<Gamemode> = listOf(Gamemode.osu),
        by: User? = editor,
    ) = Sent(description, color, confidential, gamemodes, beatmapId = "1", editor = by)

    private fun added(
        id: String,
        name: String = "User $id",
    ) = "✅ **Added [$name](https://osu.ppy.sh/users/$id)**"

    private fun removed(
        id: String,
        name: String = "User $id",
    ) = "❌ **Removed [$name](https://osu.ppy.sh/users/$id)**"

    private fun users(vararg ids: String) = ids.forEach { planner.user(it) }

    // Find

    @Test
    fun `Should find a beatmap`() {
        assertNull(service.findBeatmap("1"))
        assertNull(service.findExposedBeatmap(TOKEN, "1"))

        val bn = planner.user("5")
        val beatmap = planner.save(beatmap("1", mode(Gamemode.osu, "5", "0", firstNominated = true, isReady = true), ranked = UPDATED))

        assertEquals(beatmap, service.findBeatmap("1"))
        assertEquals(
            ExposedBeatmap(
                osuId = "1",
                artist = "Artist 1",
                title = "Title 1",
                note = "",
                mapper = mapper,
                status = BeatmapStatus.Pending,
                gamemodes =
                    listOf(
                        ExposedBeatmapGamemode(
                            Gamemode.osu,
                            listOf(ExposedBeatmapNominator(bn, true), ExposedBeatmapNominator(none, false)),
                            true,
                        ),
                    ),
                dateAdded = ADDED,
                dateUpdated = UPDATED,
                dateRanked = UPDATED,
            ),
            service.findExposedBeatmap(TOKEN, "1"),
        )
    }

    @Test
    fun `Should keep the place of nominators that are unknown`() {
        planner.save(beatmap("1", mode(Gamemode.osu, "unknown", "-1"), mode(Gamemode.taiko, "-2", "0")))

        val found = service.findExposedBeatmap(TOKEN, "1")!!

        fun unknown(id: String) = User(id, "Unknown", emptyList())
        assertEquals(
            listOf(listOf(unknown("unknown"), unknown("-1")), listOf(unknown("-2"), none)),
            found.gamemodes.map { gamemode -> gamemode.nominators.map { it.nominator } },
        )
    }

    @Test
    fun `Should not find a beatmap of an unknown mapper`() {
        planner.save(beatmap(mapperId = "unknown"))

        assertNull(service.findExposedBeatmap(TOKEN, "1"))
    }

    // Delete

    @Test
    fun `Should delete a beatmap`() {
        planner.save(beatmap("1", mode(Gamemode.osu), mode(Gamemode.mania)))
        planner.save(beatmap("2"))
        service.deleteBeatmap(TOKEN, "1")

        assertEquals(listOf("2"), planner.beatmaps.list().map { it.osuId })
        assertEquals(
            update("🐒 **Deleted$boldFooter", EmbedColor.RED, true, listOf(Gamemode.osu, Gamemode.mania)),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should not report deleting an unknown beatmap`() {
        service.deleteBeatmap(TOKEN, "1")

        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should not report a beatmap that is already deleted by someone else`() {
        val source =
            mock<BeatmapDataSource> {
                on { findById("1") } doReturn beatmap()
                on { deleteById("1") } doReturn false
            }
        BeatmapService(source, planner.userService, planner.osuClient, planner.discord).deleteBeatmap(TOKEN, "1")

        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    // Add

    @Test
    fun `Should add a beatmap`() {
        FakeOsu.beatmap("1")
        val result = service.addBeatmap("Bearer $TOKEN", NewBeatmap("1", listOf(Gamemode.fruits, Gamemode.osu)))!!

        val stored = planner.stored()
        assertRecent(stored.dateAdded)
        assertEquals(
            beatmap("1", mode(Gamemode.fruits), mode(Gamemode.osu)).copy(dateAdded = stored.dateAdded, dateUpdated = stored.dateAdded),
            stored,
        )
        assertEquals("1", result.osuId)
        assertEquals(mapper, result.mapper)
        assertRecent(result.dateAdded)
        assertEquals(result.dateAdded, result.dateUpdated)
        assertEquals(listOf("GET /api/v2/beatmapsets/1"), FakeOsu.calls())
        assertEquals("Bearer $TOKEN", FakeOsu.requests.single().header("Authorization"))
        assertEquals(emptyList<Long>(), planner.sleeps)
        assertEquals(
            update("🌟 **Created**\nGamemodes: catch, osu$footer", EmbedColor.GREEN, true, listOf(Gamemode.fruits, Gamemode.osu)),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should return the known beatmap when adding it again`() {
        planner.save(beatmap(note = "known"))

        assertEquals("known", service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.mania)))!!.note)
        assertEquals(listOf(mode(Gamemode.osu)), planner.stored().gamemodes)
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should return the beatmap someone else added in the meantime`() {
        FakeOsu.beatmap("1", userId = "11", creator = "New")
        FakeOsu.user("11", "New")
        // Finding the unknown mapper takes a while
        planner.duringSleep = { planner.save(beatmap(note = "first")) }

        assertEquals("first", service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.mania)))!!.note)
        assertEquals("first", planner.stored().note)
        assertTrue(planner.discord.sent.none { it.description.startsWith("🌟 **Created**") })
    }

    @Test
    fun `Should not add a beatmap unknown to osu`() {
        assertNull(service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.osu))))
        assertEquals(emptyList<Beatmap>(), planner.beatmaps.list())
    }

    @Test
    fun `Should find an unknown mapper when adding a beatmap`() {
        FakeOsu.beatmap("1", userId = "11", creator = "New")
        FakeOsu.user("11", "New")

        assertEquals("New", service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.osu)))!!.mapper.username)
        assertEquals(listOf("GET /api/v2/beatmapsets/1", "GET /api/v2/users/11?key=id"), FakeOsu.calls())
        assertEquals(listOf(1_000L), planner.sleeps)
        assertEquals("New", planner.users.findUser("11")!!.username)
        assertEquals(2, planner.discord.sent.size)
    }

    @Test
    fun `Should search a restricted mapper again when adding a beatmap`() {
        planner.users.saveUser(User("11", "RESTRICTED", emptyList(), restricted = true))
        planner.users.saveUser(User("12", "Fine", emptyList(), restricted = false))
        FakeOsu.beatmap("1", userId = "11")
        FakeOsu.beatmap("2", userId = "12")
        FakeOsu.user("11", "Unrestricted")

        assertEquals("Unrestricted", service.addBeatmap(TOKEN, NewBeatmap("1", emptyList()))!!.mapper.username)
        assertEquals("Fine", service.addBeatmap(TOKEN, NewBeatmap("2", emptyList()))!!.mapper.username)
        assertEquals(listOf(1_000L), planner.sleeps)
    }

    // Sync

    private fun sync(beatmap: Beatmap): Beatmap {
        planner.save(beatmap)
        service.syncBeatmap("Bearer $TOKEN", beatmap)
        return planner.stored(beatmap.osuId)
    }

    private fun nomination(
        user: String,
        vararg modes: String,
    ) = """{"beatmapset_id":1,"rulesets":[${modes.joinToString { "\"$it\"" }}],"reset":false,"user_id":$user}"""

    @Test
    fun `Should not sync a ranked beatmap`() {
        FakeOsu.beatmap("1", artist = "New")

        assertEquals(beatmap(status = BeatmapStatus.Ranked), sync(beatmap(status = BeatmapStatus.Ranked)))
        assertEquals(emptyList<String>(), FakeOsu.calls())
    }

    @Test
    fun `Should not change a beatmap unknown to osu when syncing`() {
        assertEquals(beatmap(), sync(beatmap()))
        assertEquals("Bearer $TOKEN", FakeOsu.requests.single().header("Authorization"))
    }

    @Test
    fun `Should update the details when syncing`() {
        FakeOsu.beatmap("1", artist = "New artist", title = "New title", creator = "New name", userId = "12")

        assertEquals(
            beatmap(
                artist = "New artist",
                title = "New title",
                mapper = "New name",
                note = "kept",
                status = BeatmapStatus.Reset,
                updated = Instant.parse("2024-05-06T07:08:09Z"),
            ),
            sync(beatmap(note = "kept", status = BeatmapStatus.Reset, ranked = UPDATED)),
        )
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @ParameterizedTest
    @CsvSource("1,Ranked", "2,Ranked", "3,Qualified", "4,Unfinished", "0,Unfinished", "-1,Unfinished", "-2,Unfinished")
    fun `Should update the status when syncing`(
        ranked: Int,
        expected: BeatmapStatus,
    ) {
        FakeOsu.beatmap("1", ranked = ranked)

        assertEquals(expected, sync(beatmap(status = BeatmapStatus.Unfinished)).status)
    }

    @Test
    fun `Should set the ranked date when syncing`() {
        FakeOsu.beatmap("1", ranked = 1, rankedDate = "2024-04-04T04:04:04Z")
        FakeOsu.beatmap("2", ranked = 1)

        assertEquals(Instant.parse("2024-04-04T04:04:04Z"), sync(beatmap("1")).dateRanked)
        assertRecent(sync(beatmap("2")).dateRanked)
    }

    @Test
    fun `Should determine if a beatmap is nominated, reset or disqualified when syncing`() {
        users("5")
        FakeOsu.beatmap("1", nominations = "[${nomination("5", "osu")}]")
        listOf("2", "3", "4").forEach { FakeOsu.beatmap(it) }

        assertEquals(BeatmapStatus.Nominated, sync(beatmap("1")).status)
        // Every nominator nominated it
        assertEquals(
            BeatmapStatus.Disqualified,
            sync(beatmap("2", mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true))).status,
        )
        // Some nominators nominated it
        val reset = sync(beatmap("3", mode(Gamemode.osu, "5", "6", firstNominated = true, isReady = true), mode(Gamemode.mania)))
        assertEquals(BeatmapStatus.Reset, reset.status)
        assertEquals(listOf(mode(Gamemode.osu, "5", "6", isReady = true), mode(Gamemode.mania)), reset.gamemodes)
        // Only the main gamemode needs two nominations
        assertEquals(
            BeatmapStatus.Disqualified,
            sync(
                beatmap(
                    "4",
                    mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true),
                    mode(Gamemode.mania, "5", "6", secondNominated = true),
                ),
            ).status,
        )
    }

    @Test
    fun `Should remember the possible main gamemodes when syncing`() {
        users("5")
        FakeOsu.beatmap("1", summary = """{"current":0,"eligible_main_rulesets":["taiko","osu","new-mode"]}""")
        FakeOsu.beatmap("2", summary = """{"eligible_main_rulesets":["new-mode"]}""")
        FakeOsu.beatmap("3", summary = """{"eligible_main_rulesets":null}""")
        FakeOsu.beatmap("4")
        FakeOsu.beatmap("5")

        assertEquals(listOf(Gamemode.taiko, Gamemode.osu), sync(beatmap("1")).mainGamemodes)
        // Kept as it was, when osu! doesn't tell
        listOf("2", "3", "4").forEach {
            assertEquals(listOf(Gamemode.mania), sync(beatmap(it, main = listOf(Gamemode.mania))).mainGamemodes)
        }
        assertNull(sync(beatmap("5")).mainGamemodes)
        planner.user("10", "Mapper")
        assertEquals(listOf(Gamemode.taiko, Gamemode.osu), service.findExposedBeatmap(TOKEN, "1")!!.mainGamemodes)
    }

    private fun syncNominations(
        current: BeatmapGamemode,
        vararg nominations: String,
    ): List<BeatmapGamemode> {
        users("5", "6", "7", "8")
        FakeOsu.beatmap("1", nominations = nominations.toList().toString())
        return sync(beatmap("1", current)).gamemodes
    }

    private val current = mode(Gamemode.osu, "5", "6")

    @Test
    fun `Should update the first nominator when syncing`() {
        assertEquals(listOf(mode(Gamemode.osu, "5", "6", firstNominated = true)), syncNominations(current, nomination("5", "osu")))
    }

    @Test
    fun `Should update the second nominator when syncing`() {
        assertEquals(listOf(mode(Gamemode.osu, "5", "6", secondNominated = true)), syncNominations(current, nomination("6", "osu")))
    }

    @Test
    fun `Should replace the first nominator by an unknown one when syncing`() {
        assertEquals(listOf(mode(Gamemode.osu, "7", "6", firstNominated = true)), syncNominations(current, nomination("7", "osu")))
    }

    @Test
    fun `Should replace both nominators when syncing`() {
        assertEquals(
            listOf(mode(Gamemode.osu, "8", "5", firstNominated = true, secondNominated = true)),
            syncNominations(current, nomination("8", "osu"), nomination("5", "osu")),
        )
    }

    @Test
    fun `Should only use the first two nominations when syncing`() {
        assertEquals(
            listOf(mode(Gamemode.osu, "8", "5", firstNominated = true, secondNominated = true)),
            syncNominations(current, nomination("8", "osu"), nomination("5", "osu"), nomination("6", "osu")),
        )
    }

    @Test
    fun `Should add new gamemodes when syncing`() {
        assertEquals(
            listOf(
                mode(Gamemode.osu, "5", "6", firstNominated = true),
                mode(Gamemode.fruits, "5", "7", firstNominated = true, secondNominated = true),
                mode(Gamemode.mania, "7", "0", firstNominated = true),
            ),
            syncNominations(current, nomination("5", "osu", "fruits"), nomination("7", "fruits", "mania")),
        )
    }

    @Test
    fun `Should remove gamemodes without nominations when syncing a ranked beatmap`() {
        users("5", "6")
        FakeOsu.beatmap("1", ranked = 1, nominations = "[${nomination("5", "osu")},${nomination("6", "osu")}]")

        assertEquals(
            listOf(mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true)),
            sync(beatmap("1", current, mode(Gamemode.mania, "8", "9", firstNominated = true))).gamemodes,
        )
    }

    @Test
    fun `Should keep the gamemodes when osu returns no nominations for a ranked beatmap`() {
        FakeOsu.beatmap("1", ranked = 1)

        assertEquals(listOf(current), sync(beatmap("1", current)).gamemodes)
    }

    @Test
    fun `Should find unknown nominators when syncing`() {
        FakeOsu.user("44", "New")
        FakeOsu.beatmap("1", nominations = "[${nomination("44", "osu")}]")

        assertEquals(listOf(mode(Gamemode.osu, "44", "0", firstNominated = true)), sync(beatmap()).gamemodes)
        assertEquals("New", planner.users.findUser("44")!!.username)
    }

    @Test
    fun `Should not sync when a nominator can't be found, as that would reset the nomination`() {
        FakeOsu.respond("GET /api/v2/users/44?key=id", "{}", status = 503)
        FakeOsu.beatmap("1", title = "New", nominations = "[${nomination("44", "osu")}]")
        val beatmap = beatmap("1", mode(Gamemode.osu, "44", "0", firstNominated = true))

        assertEquals(beatmap, sync(beatmap))
    }

    @Test
    fun `Should not add a beatmap when the mapper can't be found`() {
        FakeOsu.beatmap("1", userId = "11")
        FakeOsu.respond("GET /api/v2/users/11?key=id", "{}", status = 503)

        assertNull(service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.osu))))
        assertEquals(emptyList<Beatmap>(), planner.beatmaps.list())

        // Already known as restricted
        planner.users.saveUser(User("11", "RESTRICTED", emptyList(), restricted = true))
        assertEquals("RESTRICTED", service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.osu)))!!.mapper.username)
    }

    @Test
    fun `Should leave out a gamemode the planner does not know when syncing`() {
        users("5")
        FakeOsu.beatmap("1", nominations = "[${nomination("5", "new-mode")},${nomination("5", "osu")}]")

        assertEquals(listOf(mode(Gamemode.osu, "5", "0", firstNominated = true)), sync(beatmap()).gamemodes)
    }

    // Note

    @Test
    fun `Should update the note`() {
        planner.save(beatmap(note = "old"))

        assertTrue(service.updateBeatmapNote(TOKEN, "1", "first\n    second\n third"))
        assertEquals("first\n    second\n third", planner.stored().note)
        assertRecent(planner.stored().dateUpdated)
        assertEquals(
            // Only a line starting with at least two spaces is trimmed
            update("🗒 **Updated note**\n```first\nsecond\n third```$footer", EmbedColor.ORANGE, true),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should clear the note`() {
        planner.save(beatmap(note = "old"))

        assertTrue(service.updateBeatmapNote(TOKEN, "1", ""))
        assertEquals("", planner.stored().note)
        assertEquals(update("🗒 **Removed note**$footer", EmbedColor.ORANGE, true), planner.discord.single())
    }

    @Test
    fun `Should not update an unchanged note or an unknown beatmap`() {
        planner.save(beatmap(note = "same"))

        assertTrue(service.updateBeatmapNote(TOKEN, "1", "same"))
        assertFalse(service.updateBeatmapNote(TOKEN, "2", "new"))
        assertEquals(beatmap(note = "same"), planner.stored())
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    // Status

    @ParameterizedTest
    @CsvSource(
        "Qualified,❤",
        "Nominated,💭",
        "Disqualified,💔",
        "Reset,🗯",
        "Ranked,💖",
        "Graved,🗑",
        "Unfinished,🔨",
    )
    fun `Should update the status`(
        status: BeatmapStatus,
        icon: String,
    ) {
        planner.save(beatmap())

        assertTrue(service.updateBeatmapStatus(TOKEN, "1", status))
        assertEquals(status, planner.stored().status)
        if (status == BeatmapStatus.Ranked) {
            assertRecent(planner.stored().dateRanked!!)
        } else {
            assertNull(planner.stored().dateRanked)
        }
        assertRecent(planner.stored().dateUpdated)
        assertEquals(update("$icon **Updated status to $status$boldFooter", EmbedColor.ORANGE, true), planner.discord.single())
    }

    @Test
    fun `Should update the status to pending`() {
        planner.save(beatmap(status = BeatmapStatus.Ranked, ranked = UPDATED))

        assertTrue(service.updateBeatmapStatus(TOKEN, "1", BeatmapStatus.Pending))
        // No longer ranked
        assertNull(planner.stored().dateRanked)
        assertEquals(update("⏳ **Updated status to Pending$boldFooter", EmbedColor.ORANGE, true), planner.discord.single())
    }

    @Test
    fun `Should not update an unchanged status or an unknown beatmap`() {
        planner.save(beatmap())

        assertTrue(service.updateBeatmapStatus(TOKEN, "1", BeatmapStatus.Pending))
        assertFalse(service.updateBeatmapStatus(TOKEN, "2", BeatmapStatus.Pending))
        assertEquals(beatmap(), planner.stored())

        val ranked = beatmap("3", status = BeatmapStatus.Ranked, ranked = UPDATED)
        planner.save(ranked)
        assertTrue(service.updateBeatmapStatus(TOKEN, "3", BeatmapStatus.Ranked))
        assertEquals(ranked, planner.beatmaps.findById("3"))
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    // Nominators

    @Test
    fun `Should only mention the gamemode of a changed nominator when there are several`() {
        val new = planner.user("5")
        val old = planner.user("6")

        val mentioned = "${added("5")} [catch]\n${removed("6")} [catch]"
        val notMentioned = "${added("5")}\n${removed("6")}"
        assertEquals(mentioned, service.getChangedNominatorText(new, old, true, Gamemode.fruits))
        assertEquals(notMentioned, service.getChangedNominatorText(new, old, false, Gamemode.fruits))
        assertEquals(notMentioned, service.getChangedNominatorText(new, old, true, null))
        assertEquals(notMentioned, service.getChangedNominatorText(new, old, null, null))
        assertEquals(notMentioned, service.getChangedNominatorText(new, old))
        assertEquals(added("5"), service.getChangedNominatorText(new, null))
        assertEquals(removed("6"), service.getChangedNominatorText(null, old))
        assertEquals("", service.getChangedNominatorText(null, none))
        assertEquals("", service.getChangedNominatorText(null, null))
    }

    private fun updateNominator(
        old: String,
        new: String,
        gamemode: Gamemode = Gamemode.osu,
    ) = service.updateBeatmapNominator(TOKEN, "1", gamemode, old, new)

    @Test
    fun `Should update the first nominator`() {
        users("5", "6", "7")
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true, isReady = true)))
        val result = updateNominator("5", "7")!!

        assertEquals(listOf(mode(Gamemode.osu, "7", "6", secondNominated = true, isReady = true)), planner.stored().gamemodes)
        assertRecent(planner.stored().dateUpdated)
        assertEquals(listOf("7", "6"), result.gamemodes[0].nominators.map { it.nominator.osuId })
        assertEquals(update("${added("7")}\n${removed("5")}$footer [osu]", EmbedColor.BLUE, false), planner.discord.single())
    }

    @Test
    fun `Should update the second nominator`() {
        users("5", "6")
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true)))
        updateNominator("6", "0")

        assertEquals(listOf(mode(Gamemode.osu, "5", "0", firstNominated = true)), planner.stored().gamemodes)
        assertEquals(update("${removed("6")}$footer [osu]", EmbedColor.BLUE, false), planner.discord.single())
    }

    @Test
    fun `Should not change anything when the old nominator is already replaced`() {
        users("5", "6", "7", "8")
        val beatmap = planner.save(beatmap("1", mode(Gamemode.osu, "5", "6", secondNominated = true)))

        assertNull(updateNominator("8", "7"))
        assertNull(updateNominator("8", "7", Gamemode.mania))
        assertEquals(beatmap, planner.stored())
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should not set the same nominator twice`() {
        users("5")
        planner.save(beatmap("1", mode(Gamemode.osu, "5")))

        assertNull(updateNominator("0", "5"))
        assertEquals(listOf(mode(Gamemode.osu, "5")), planner.stored().gamemodes)
    }

    @Test
    fun `Should add a gamemode when setting its nominator`() {
        users("5")
        planner.save(beatmap())
        updateNominator("0", "5", Gamemode.fruits)

        assertEquals(listOf(mode(Gamemode.osu), mode(Gamemode.fruits, "5")), planner.stored().gamemodes)
        assertEquals(
            update("${added("5")} [catch]$footer [osu, catch]", EmbedColor.BLUE, false, listOf(Gamemode.osu, Gamemode.fruits)),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should remove a gamemode without nominators when there are others`() {
        users("5", "6")
        planner.save(beatmap("1", mode(Gamemode.osu, "5"), mode(Gamemode.fruits, "0", "6")))
        val result = updateNominator("5", "0")!!

        assertEquals(listOf(mode(Gamemode.fruits, "0", "6")), planner.stored().gamemodes)
        assertEquals(listOf(Gamemode.fruits), result.gamemodes.map { it.gamemode })
        // Also reported to the removed gamemode
        assertEquals(
            update("${removed("5")} [osu]$footer [osu, catch]", EmbedColor.BLUE, false, listOf(Gamemode.osu, Gamemode.fruits)),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should keep the last gamemode when it has no nominators`() {
        users("5")
        planner.save(beatmap("1", mode(Gamemode.osu, "5")))
        updateNominator("5", "0")

        assertEquals(listOf(mode(Gamemode.osu)), planner.stored().gamemodes)
    }

    @Test
    fun `Should keep a gamemode having only a second nominator`() {
        users("5", "6")
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "6"), mode(Gamemode.mania)))
        updateNominator("5", "0")

        assertEquals(listOf(mode(Gamemode.mania), mode(Gamemode.osu, "0", "6")), planner.stored().gamemodes)
    }

    @Test
    fun `Should not update a nominator when the gamemode could not be updated`() {
        // Never the case at the moment, as a missing gamemode is added
        planner.save(beatmap())
        val service = spy(service)
        doReturn(null).whenever(service).updateBeatmapGamemode(any(), any<Gamemode>(), any())

        assertNull(service.updateBeatmapNominator(TOKEN, "1", Gamemode.osu, "0", "5"))
        assertEquals(beatmap(), planner.stored())
    }

    @Test
    fun `Should move the updated gamemode to the end`() {
        users("5")
        planner.save(beatmap("1", mode(Gamemode.osu), mode(Gamemode.mania)))
        updateNominator("0", "5")

        assertEquals(listOf(mode(Gamemode.mania), mode(Gamemode.osu, "5")), planner.stored().gamemodes)
    }

    @Test
    fun `Should not update a nominator of an unknown beatmap`() {
        assertNull(updateNominator("0", "5"))
    }

    @Test
    fun `Should report without an editor when the token is unreadable`() {
        planner.save(beatmap("1", mode(Gamemode.osu, "5")))
        service.updateBeatmapNominator("unreadable", "1", Gamemode.osu, "5", "0")
        service.updateBeatmapStatus("unreadable", "1", BeatmapStatus.Reset)
        service.updateBeatmapNote("unreadable", "1", "note")
        service.deleteBeatmap("unreadable", "1")
        service.logBeatmapAdded("unreadable", beatmap())

        assertEquals(5, planner.discord.sent.size)
        assertTrue(planner.discord.sent.all { it.editor == null })
    }

    @Test
    fun `Should work without Discord`() {
        val planner = Planner(withDiscord = false)
        val service = planner.beatmapService
        planner.user("10")
        FakeOsu.beatmap("1")

        service.addBeatmap(TOKEN, NewBeatmap("1", listOf(Gamemode.osu)))
        service.updateBeatmapNote(TOKEN, "1", "note")
        service.updateBeatmapStatus(TOKEN, "1", BeatmapStatus.Reset)
        service.updateBeatmapNominator(TOKEN, "1", Gamemode.osu, "0", "10")
        assertEquals("note", planner.stored().note)
        assertEquals(BeatmapStatus.Reset, planner.stored().status)
        assertEquals(listOf(mode(Gamemode.osu, "10")), planner.stored().gamemodes)
        service.deleteBeatmap(TOKEN, "1")
        assertEquals(emptyList<Beatmap>(), planner.beatmaps.list())
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }
}
