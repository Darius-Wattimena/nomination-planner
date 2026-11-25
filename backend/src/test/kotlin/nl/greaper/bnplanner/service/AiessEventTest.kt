package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.aiess.AiessBeatmapEvent
import nl.greaper.bnplanner.model.aiess.AiessResponse
import nl.greaper.bnplanner.model.aiess.AiessUserEvent
import nl.greaper.bnplanner.model.aiess.AiessUserEventType
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.assertRecent
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.RecordingDiscord.Sent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

/** Events of Aiess up to what is stored in a real MongoDB and sent to Discord, see AiessServiceTest for the service by itself */
class AiessEventTest {
    private val planner = Planner()
    private val service = planner.aiessService

    private val footer =
        "\n**[Artist 1 - Title 1](https://osu.ppy.sh/beatmapsets/1)**\nMapped by [Mapper](https://osu.ppy.sh/users/10)"

    private fun event(
        status: BeatmapStatus,
        userId: String? = "5",
        username: String? = "BN",
        modes: List<Gamemode> = listOf(Gamemode.osu),
    ) = AiessBeatmapEvent("1", "Artist 1", "Title 1", "Mapper", "10", status, userId, username, modes)

    private fun process(event: AiessBeatmapEvent): Beatmap {
        assertEquals(AiessResponse(true), service.processAiessBeatmapEvent(event))
        return planner.stored()
    }

    private fun sent(
        description: String,
        color: EmbedColor,
        footer: EmbedFooter = EmbedFooter("Aiess"),
        confidential: Boolean = true,
        gamemodes: List<Gamemode> = listOf(Gamemode.osu),
    ) = Sent(description, color, confidential, gamemodes, EmbedThumbnail("https://b.ppy.sh/thumb/1l.jpg"), footer)

    private val bnFooter = EmbedFooter("BN", "https://a.ppy.sh/5")

    // Instant statuses

    @ParameterizedTest
    @CsvSource("Ranked,💖,GREEN", "Graved,🗑,GREY", "Pending,💭,BLUE", "Unfinished,🔨,PURPLE")
    fun `Should process a status without a user`(
        status: BeatmapStatus,
        icon: String,
        color: EmbedColor,
    ) {
        planner.save(beatmap("1", mode(Gamemode.osu, "5", "6", firstNominated = true), status = BeatmapStatus.Qualified, note = "kept"))
        val stored = process(event(status, null, null, emptyList()))

        assertRecent(stored.dateUpdated)
        // SUSPECTED BUG - Any of these statuses sets the ranked date
        assertEquals(stored.dateUpdated, stored.dateRanked)
        assertEquals(
            beatmap(
                "1",
                mode(Gamemode.osu, "5", "6", firstNominated = true),
                status = status,
                note = "kept",
                updated = stored.dateUpdated,
                ranked = stored.dateRanked,
            ),
            stored,
        )
        assertEquals(listOf(sent("**$icon Updated status to $status**$footer", color)), planner.discord.sent)
    }

    @Test
    fun `Should create an unknown beatmap on a status without a user`() {
        planner.user("10")
        val stored = process(event(BeatmapStatus.Ranked, "5", "BN", listOf(Gamemode.osu, Gamemode.mania)))

        assertRecent(stored.dateAdded)
        assertEquals(BeatmapStatus.Ranked, stored.status)
        assertEquals(listOf(mode(Gamemode.osu), mode(Gamemode.mania)), stored.gamemodes)
        assertEquals("User 10", planner.users.findUser("10")!!.username)
        val modes = listOf(Gamemode.osu, Gamemode.mania)
        assertEquals(
            listOf(
                sent("🌟 **Created**$footer", EmbedColor.GREEN, gamemodes = modes),
                sent("**💖 Updated status to Ranked**$footer", EmbedColor.GREEN, gamemodes = modes),
            ),
            planner.discord.sent,
        )
    }

    // Statuses with a user

    @ParameterizedTest
    @CsvSource("Qualified,❤,BLUE", "Nominated,💭,BLUE")
    fun `Should process a nomination`(
        status: BeatmapStatus,
        icon: String,
        color: EmbedColor,
    ) {
        planner.user("5", "BN")
        planner.save(beatmap("1", mode(Gamemode.fruits, "5", "6", secondNominated = true, isReady = true), ranked = UPDATED))
        val stored = process(event(status, modes = listOf(Gamemode.fruits)))

        assertRecent(stored.dateUpdated)
        assertEquals(
            beatmap(
                "1",
                mode(Gamemode.fruits, "5", "6", firstNominated = true, secondNominated = true, isReady = true),
                status = status,
                updated = stored.dateUpdated,
                ranked = UPDATED,
            ),
            stored,
        )
        assertEquals(
            sent("**$icon Updated status to $status**$footer [catch]", color, bnFooter, gamemodes = listOf(Gamemode.fruits)),
            planner.discord.single(),
        )
    }

    private fun nominate(current: BeatmapGamemode): BeatmapGamemode {
        listOf("5", "6", "7").forEach { planner.user(it) }
        planner.save(beatmap("1", current))
        return process(event(BeatmapStatus.Nominated)).gamemodes.single()
    }

    private fun nominatorChange(text: String) = sent("$text$footer [osu]", EmbedColor.BLUE, confidential = false)

    private val nomination = sent("**💭 Updated status to Nominated**$footer [osu]", EmbedColor.BLUE, bnFooter)
    private val added = "✅ **Added [User 5](https://osu.ppy.sh/users/5)**"

    @Test
    fun `Should nominate as the first nominator`() {
        assertEquals(mode(Gamemode.osu, "5", "6", firstNominated = true), nominate(mode(Gamemode.osu, "5", "6")))
        assertEquals(listOf(nomination), planner.discord.sent)
    }

    @Test
    fun `Should nominate as the second nominator`() {
        assertEquals(mode(Gamemode.osu, "0", "5", secondNominated = true), nominate(mode(Gamemode.osu, "0", "5")))
        assertEquals(listOf(nomination), planner.discord.sent)
    }

    @Test
    fun `Should nominate as a missing first nominator`() {
        assertEquals(mode(Gamemode.osu, "5", "6", firstNominated = true), nominate(mode(Gamemode.osu, "0", "6")))
        assertEquals(listOf(nominatorChange(added), nomination), planner.discord.sent)
    }

    @Test
    fun `Should nominate as a missing second nominator`() {
        assertEquals(mode(Gamemode.osu, "6", "5", secondNominated = true), nominate(mode(Gamemode.osu, "6", "0")))
        assertEquals(listOf(nominatorChange(added), nomination), planner.discord.sent)
    }

    @Test
    fun `Should replace the first nominator that did not nominate`() {
        assertEquals(mode(Gamemode.osu, "5", "7", firstNominated = true), nominate(mode(Gamemode.osu, "6", "7")))
        assertEquals(
            listOf(nominatorChange("$added\n❌ **Removed [User 6](https://osu.ppy.sh/users/6)**"), nomination),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should replace the second nominator that did not nominate`() {
        assertEquals(
            mode(Gamemode.osu, "6", "5", firstNominated = true, secondNominated = true),
            nominate(mode(Gamemode.osu, "6", "7", firstNominated = true)),
        )
        assertEquals(
            listOf(nominatorChange("$added\n❌ **Removed [User 7](https://osu.ppy.sh/users/7)**"), nomination),
            planner.discord.sent,
        )
    }

    @Test
    fun `Should keep both nominators when they already nominated`() {
        val current = mode(Gamemode.osu, "6", "7", firstNominated = true, secondNominated = true)

        assertEquals(current, nominate(current))
        assertEquals(BeatmapStatus.Nominated, planner.stored().status)
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should nominate several gamemodes, adding missing ones`() {
        planner.user("5")
        planner.save(beatmap("1", mode(Gamemode.osu), mode(Gamemode.taiko)))
        val stored = process(event(BeatmapStatus.Nominated, modes = listOf(Gamemode.osu, Gamemode.mania)))

        assertEquals(
            listOf(mode(Gamemode.taiko), mode(Gamemode.osu, "5", firstNominated = true), mode(Gamemode.mania, "5", firstNominated = true)),
            stored.gamemodes,
        )
        assertEquals(4, planner.discord.sent.size)
    }

    @Test
    fun `Should create an unknown beatmap, mapper and nominator on a nomination`() {
        val stored = process(event(BeatmapStatus.Nominated))

        assertRecent(stored.dateAdded)
        assertEquals(
            beatmap(
                "1",
                mode(Gamemode.osu, "5", firstNominated = true),
                status = BeatmapStatus.Nominated,
                updated = stored.dateUpdated,
            ).copy(dateAdded = stored.dateAdded),
            stored,
        )
        assertEquals(
            listOf(
                User("10", "Mapper", listOf(UserGamemode(Gamemode.osu, Role.Mapper))),
                User("5", "BN", listOf(UserGamemode(Gamemode.osu, Role.Nominator))),
            ),
            planner.users.list(),
        )
        assertEquals(sent("🌟 **Created**$footer", EmbedColor.BLUE, bnFooter), planner.discord.sent[0])
        assertEquals(3, planner.discord.sent.size)
    }

    @Test
    fun `Should only create an unknown beatmap using a complete user`() {
        service.createFallbackBeatmap(event(BeatmapStatus.Nominated), "5", null)
        service.createFallbackBeatmap(event(BeatmapStatus.Nominated), null, "BN")

        assertEquals(listOf(EmbedFooter("Aiess"), EmbedFooter("Aiess")), planner.discord.sent.map { it.footer })
    }

    @ParameterizedTest
    @CsvSource("Disqualified,💔", "Reset,🗯")
    fun `Should reset the nominations`(
        status: BeatmapStatus,
        icon: String,
    ) {
        planner.save(
            beatmap(
                "1",
                mode(Gamemode.osu, "5", "6", firstNominated = true, secondNominated = true),
                mode(Gamemode.mania, "0", "7", false, true),
            ),
        )
        val stored = process(event(status, modes = emptyList()))

        assertRecent(stored.dateUpdated)
        assertEquals(status, stored.status)
        assertEquals(listOf(mode(Gamemode.osu, "5", "6"), mode(Gamemode.mania, "0", "7")), stored.gamemodes)
        assertEquals(
            sent(
                "**$icon Updated status to $status**$footer []",
                EmbedColor.ORANGE,
                bnFooter,
                gamemodes = listOf(Gamemode.osu, Gamemode.mania),
            ),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should create an unknown beatmap on a reset`() {
        assertEquals(BeatmapStatus.Reset, process(event(BeatmapStatus.Reset)).status)
        assertEquals(sent("🌟 **Created**$footer", EmbedColor.ORANGE, bnFooter), planner.discord.sent[0])
    }

    @Test
    fun `Should fail on a nomination without modes`() {
        planner.save(beatmap())

        assertEquals(
            AiessResponse(false, "No modes are provided"),
            service.processAiessBeatmapEvent(event(BeatmapStatus.Nominated, modes = emptyList())),
        )
        assertEquals(beatmap(), planner.stored())
    }

    @Test
    fun `SUSPECTED BUG - An unknown beatmap is reported as created but not stored when no modes are provided`() {
        service.processAiessBeatmapEvent(event(BeatmapStatus.Nominated, modes = emptyList()))

        assertEquals(emptyList<Beatmap>(), planner.beatmaps.list())
        assertEquals("🌟 **Created**$footer", planner.discord.single().description)
    }

    @Test
    fun `Should ignore and report an incomplete event`() {
        planner.save(beatmap())

        assertEquals(beatmap(), process(event(BeatmapStatus.Nominated, null, "BN")))
        assertEquals(beatmap(), process(event(BeatmapStatus.Reset, "5", null)))
        val description = planner.discord.sent[0].description
        val expected =
            """
            {"beatmapSetId":"1","artist":"Artist 1","title":"Title 1","creatorName":"Mapper","creatorId":"10",
            "status":"Nominated","userId":null,"userName":"BN","modes":["osu"]}
            """
        val json = description.removePrefix("**ERROR: Incomplete AiessBeatmapEvent**\n```").removeSuffix("```")
        JSONAssert.assertEquals(expected, json, JSONCompareMode.STRICT)
        assertEquals(sent(description, EmbedColor.RED, gamemodes = emptyList()), planner.discord.sent[0])
        assertEquals(2, planner.discord.sent.size)
    }

    // Users

    private val bn = UserGamemode(Gamemode.osu, Role.Nominator)

    private fun userEvent(
        type: AiessUserEventType,
        group: String = "28",
        gamemode: Gamemode = Gamemode.osu,
    ) = service.processAiessUserEvent(AiessUserEvent(type, "5", "BN", group, gamemode)).also { assertEquals(AiessResponse(true), it) }

    private fun assertUser(vararg gamemodes: UserGamemode) {
        val expected = User("5", "BN", gamemodes.toList())
        assertEquals(expected, planner.users.findUser("5"))
        assertEquals(listOf(expected), planner.users.list())
    }

    private fun moved(
        description: String,
        color: EmbedColor,
    ) = Sent(description, color, true, emptyList(), EmbedThumbnail("https://a.ppy.sh/5"), EmbedFooter("Aiess"))

    private val link = "[BN](https://osu.ppy.sh/users/5)"

    @ParameterizedTest
    @CsvSource(
        "28,Nominator,Beatmap Nominators",
        "32,Probation,Beatmap Nominators (Probationary)",
        "7,NominationAssessment,Nomination Assessment Team",
    )
    fun `Should add an unknown user to a group`(
        group: String,
        role: Role,
        name: String,
    ) {
        userEvent(AiessUserEventType.add, group, Gamemode.fruits)

        assertUser(UserGamemode(Gamemode.fruits, role))
        assertEquals(moved("**Added $link\nto $name**\nGamemode fruits", EmbedColor.GREEN), planner.discord.single())
    }

    @Test
    fun `Should add another gamemode to a user`() {
        planner.user("5", "BN", bn)
        userEvent(AiessUserEventType.add, gamemode = Gamemode.mania)

        assertUser(bn, UserGamemode(Gamemode.mania, Role.Nominator))
    }

    @Test
    fun `SUSPECTED BUG - Moving to another role in the same gamemode keeps the old role`() {
        planner.user("5", "BN", UserGamemode(Gamemode.osu, Role.Probation))
        userEvent(AiessUserEventType.add)

        assertUser(UserGamemode(Gamemode.osu, Role.Probation), bn)
        assertEquals("**Added $link\nto Beatmap Nominators**\nGamemode osu", planner.discord.single().description)
    }

    @Test
    fun `SUSPECTED BUG - A user is only moved when nothing changes`() {
        planner.user("5", "BN", UserGamemode(Gamemode.taiko, Role.Probation), bn, UserGamemode(Gamemode.osu, Role.Probation))
        userEvent(AiessUserEventType.add)

        // The other role of the same gamemode is removed
        assertUser(UserGamemode(Gamemode.taiko, Role.Probation), bn)
        assertEquals(
            moved("**Moved $link\nfrom Beatmap Nominators to Beatmap Nominators**\nGamemode osu", EmbedColor.GREEN),
            planner.discord.single(),
        )
    }

    @Test
    fun `Should not add a user to an unsupported group`() {
        userEvent(AiessUserEventType.add, "16")

        assertNull(planner.users.findUser("5"))
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should remove a user from a group`() {
        planner.user("5", "BN", bn, UserGamemode(Gamemode.mania, Role.Probation))
        userEvent(AiessUserEventType.remove)

        assertUser(UserGamemode(Gamemode.mania, Role.Probation))
        assertEquals(moved("**Removed $link\nfrom Beatmap Nominators**\nGamemode osu", EmbedColor.RED), planner.discord.single())
    }

    @Test
    fun `Should remove the mapper role using an unsupported group`() {
        planner.user("5", "BN", UserGamemode(Gamemode.osu, Role.Mapper))
        userEvent(AiessUserEventType.remove, "16")

        assertUser()
        assertEquals(moved("**Removed $link\nfrom Mapper**\nGamemode osu", EmbedColor.RED), planner.discord.single())
    }

    @Test
    fun `Should only remove the role of the group`() {
        val nat = UserGamemode(Gamemode.osu, Role.NominationAssessment)
        planner.user("5", "BN", bn, nat)
        userEvent(AiessUserEventType.remove)

        assertUser(nat)
    }

    @Test
    fun `Should move a user to another group, regardless of the order of the events`() {
        val nat = UserGamemode(Gamemode.osu, Role.NominationAssessment)
        planner.user("5", "BN", bn)
        userEvent(AiessUserEventType.add, "7")
        userEvent(AiessUserEventType.remove, "28")
        assertUser(nat)

        userEvent(AiessUserEventType.remove, "7")
        userEvent(AiessUserEventType.add, "28")
        assertUser(bn)
    }

    @Test
    fun `Should not remove a user from a group they are not part of`() {
        planner.user("5", "BN", bn)
        userEvent(AiessUserEventType.remove, "7")
        userEvent(AiessUserEventType.remove, "28", Gamemode.mania)

        assertUser(bn)
        assertEquals(emptyList<Sent>(), planner.discord.sent)
    }

    @Test
    fun `Should report a move without any gamemode as error`() {
        service.logUserMove(
            User("5", "BN", emptyList()),
            AiessUserEvent(AiessUserEventType.remove, "5", "BN", "28", Gamemode.osu),
            null,
            null,
        )

        assertEquals(moved("**ERROR? User moved happened but no clue what**\nGamemode osu", EmbedColor.RED), planner.discord.single())
    }

    @Test
    fun `Should work without Discord`() {
        val planner = Planner(withDiscord = false)
        val service = planner.aiessService

        service.processAiessBeatmapEvent(event(BeatmapStatus.Nominated))
        service.processAiessBeatmapEvent(event(BeatmapStatus.Reset))
        service.processAiessBeatmapEvent(event(BeatmapStatus.Reset, null))
        service.processAiessBeatmapEvent(event(BeatmapStatus.Ranked))
        service.processAiessUserEvent(AiessUserEvent(AiessUserEventType.add, "5", "BN", "7", Gamemode.mania))

        assertEquals(BeatmapStatus.Ranked, planner.stored().status)
        assertEquals(listOf(bn, UserGamemode(Gamemode.mania, Role.NominationAssessment)), planner.users.findUser("5")!!.gamemodes)
    }
}
