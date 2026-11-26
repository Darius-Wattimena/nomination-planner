package nl.greaper.bnplanner.config

import com.mongodb.client.result.InsertOneResult
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.utils.data.DataObject
import nl.greaper.bnplanner.client.DiscordBotClient
import nl.greaper.bnplanner.datasource.DiscordEventListenerDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.discord.DiscordEventListener
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.support.FakeJda
import nl.greaper.bnplanner.support.TestMongo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class DiscordBotTest {
    private val discord = FakeJda()
    private val source by lazy { DiscordEventListenerDataSource(TestMongo.database) }

    @BeforeEach
    fun setup() = TestMongo.clear()

    private val red = 0xFF0000
    private val green = 0x00FF00

    private fun assertAnswer(
        description: String,
        color: Int,
    ) {
        val message = discord.messages.single()
        assertNull(message.channel)
        assertEquals(description, message.embed.getString("description"))
        assertEquals(color, message.embed.getInt("color"))
        assertEquals(1, discord.deferred)
        discord.messages.clear()
        discord.deferred = 0
    }

    private fun assertNoAnswer() {
        assertEquals(emptyList<Any>(), discord.messages + discord.replies)
        assertEquals(0, discord.deferred)
    }

    private fun listener(
        channel: String = "1",
        gamemode: Gamemode? = null,
        confidential: Boolean = false,
    ) = DiscordEventListener("guild", channel, gamemode, confidential)

    // Register

    @Test
    fun `Should register a channel for all gamemodes`() {
        discord.slashCommand(source, subcommand = "register")

        assertAnswer("**Registered listener at channel**\nGamemode = `all`", green)
        assertEquals(listOf(listener()), source.list())
        assertEquals(listOf(listener()), source.getListeners())
    }

    @Test
    fun `Should register a channel for a gamemode`() {
        discord.slashCommand(source, subcommand = "register", gamemode = "Fruits")

        assertAnswer("**Registered listener at channel**\nGamemode = `catch`", green)
        assertEquals(listOf(listener(gamemode = Gamemode.fruits)), source.list())
    }

    @Test
    fun `Should change the gamemode of a registered channel`() {
        source.create(listener("2"))
        source.create(listener())
        discord.slashCommand(source, subcommand = "register", gamemode = "mania")

        assertAnswer("**Registered listener at channel**\nGamemode = `mania`", green)
        assertEquals(setOf(listener("2"), listener(gamemode = Gamemode.mania)), source.list().toSet())
    }

    @Test
    fun `Should keep a confidential channel confidential when registering it again`() {
        source.create(listener(confidential = true))
        discord.slashCommand(source, subcommand = "register")

        assertAnswer("**Registered listener at channel**\nGamemode = `all`", green)
        assertEquals(listOf(listener(confidential = true)), source.list())
    }

    @Test
    fun `Should only register a channel as administrator`() {
        val description = "**You need to be an administrator to execute this command**"
        discord.slashCommand(source, subcommand = "register", admin = false)
        assertAnswer(description, red)
        discord.slashCommand(source, subcommand = "register", admin = null)
        assertAnswer(description, red)
        discord.slashCommand(source, subcommand = "register", permissions = false)
        assertAnswer(description, red)

        assertEquals(emptyList<DiscordEventListener>(), source.list())
    }

    private val registerFailed = "**ERROR: Could not register listener!**\nPlease try again.\nIf this keeps occurring, contact Greaper"

    @Test
    fun `Should tell that registering failed`() {
        // Known by the bot, but gone from the database
        source.create(listener())
        TestMongo.clear()
        discord.slashCommand(source, subcommand = "register")
        assertAnswer(registerFailed, red)

        val failing = mock<DiscordEventListenerDataSource> { on { create(any()) } doReturn InsertOneResult.unacknowledged() }
        discord.slashCommand(failing, subcommand = "register")
        assertAnswer(registerFailed, red)
    }

    @Test
    fun `Should refuse an unknown gamemode right away`() {
        discord.slashCommand(source, subcommand = "register", gamemode = "catch")

        assertEquals(listOf("Unknown gamemode `catch`"), discord.replies)
        assertEquals(0, discord.deferred)
        assertEquals(emptyList<DiscordEventListener>(), source.list())
    }

    // Remove

    @Test
    fun `Should remove a channel`() {
        source.create(listener())
        source.create(listener("2"))
        discord.slashCommand(source, subcommand = "remove")

        assertAnswer("**Removed listener**", green)
        assertEquals(listOf(listener("2")), source.list())
        assertEquals(listOf(listener("2")), source.getListeners())
    }

    @Test
    fun `Should only remove a channel as administrator`() {
        source.create(listener())
        val description = "**You need to be an administrator to execute this command**"
        discord.slashCommand(source, subcommand = "remove", admin = false)
        assertAnswer(description, red)
        discord.slashCommand(source, subcommand = "remove", admin = null)
        assertAnswer(description, red)
        discord.slashCommand(source, subcommand = "remove", permissions = false)
        assertAnswer(description, red)

        assertEquals(listOf(listener()), source.list())
    }

    @Test
    fun `Should not remove a channel that isn't registered`() {
        source.create(listener("2"))
        discord.slashCommand(source, subcommand = "remove")

        assertAnswer("**ERROR: Could not remove listener!**\nNothing is registered for this channel", red)
    }

    @Test
    fun `Should tell that removing failed`() {
        source.create(listener())
        TestMongo.clear()
        discord.slashCommand(source, subcommand = "remove")

        assertAnswer("**ERROR: Could not remove listener!**\nPlease try again.\nIf this keeps occurring, contact Greaper", red)
    }

    // Other commands

    @Test
    fun `Should ignore other commands`() {
        discord.slashCommand(source, subcommand = "status", isFromGuild = false)
        discord.slashCommand(source, name = "other", subcommand = "status")
        discord.slashCommand(source, subcommand = "other")
        discord.slashCommand(source, subcommand = null)
        discord.slashCommand(source, subcommand = "other", guildId = null)

        assertNoAnswer()
    }

    private fun subcommands(): Map<String, DataObject> {
        val commands = discord.commands!!
        assertEquals("planner", commands.getString("name"))
        assertEquals("All nomination planner slash commands", commands.getString("description"))
        val options = commands.getArray("options")
        return (0 until options.length()).map { options.getObject(it) }.associateBy { it.getString("name") }
    }

    @Test
    fun `Should add the commands to a server`() {
        discord.guildReady(source, "1")
        val commands = subcommands()

        assertEquals(listOf("register", "remove"), commands.keys.toList())
        assertTrue(commands.values.all { it.getInt("type") == 1 })
        assertEquals("Register a listener of bnplanner events", commands["register"]!!.getString("description"))
        assertEquals("Remove a listener of bnplanner events", commands["remove"]!!.getString("description"))

        val option = commands["register"]!!.getArray("options").getObject(0)
        assertEquals("gamemode", option.getString("name"))
        assertEquals("The osu! gamemode the event is for", option.getString("description"))
        assertEquals(3, option.getInt("type"))
        assertFalse(option.getBoolean("required", false))
        val choices = option.getArray("choices")
        assertEquals(
            listOf("osu" to "osu", "taiko" to "taiko", "catch" to "fruits", "mania" to "mania"),
            (0 until choices.length()).map { choices.getObject(it).getString("name") to choices.getObject(it).getString("value") },
        )
    }

    @Test
    fun `Should log in using the configured token`() {
        val jda = mock<JDA>()
        val builder = mock<JDABuilder>()
        Mockito.`when`(builder.addEventListeners(any<DiscordConfig.DiscordBotHandler>())).thenReturn(builder)
        Mockito.`when`(builder.build()).thenReturn(jda)

        Mockito.mockStatic(JDABuilder::class.java).use { builders ->
            builders.`when`<JDABuilder> { JDABuilder.createDefault("made-up-token") }.thenReturn(builder)
            assertSame(jda, DiscordConfig(source).jda(DiscordProperties("made-up-token")))
        }
    }

    // Sending events

    private val client by lazy { DiscordBotClient(source, discord.jda) }

    private fun send(
        confidential: Boolean,
        gamemodes: List<Gamemode>,
        thumbnail: String = "https://a.ppy.sh/1",
        footer: EmbedFooter = EmbedFooter("Aiess"),
    ): List<String?> {
        discord.messages.clear()
        client.send("text", EmbedColor.BLUE, EmbedThumbnail(thumbnail), footer, confidential, gamemodes)
        return discord.messages.map { it.channel }
    }

    private fun addListeners() {
        listOf(
            listener("all"),
            listener("osu", Gamemode.osu),
            listener("mania", Gamemode.mania),
            listener("secret", confidential = true),
            listener("secret-osu", Gamemode.osu, confidential = true),
        ).forEach {
            source.create(it)
            discord.addChannel(it.channelId)
        }
    }

    @Test
    fun `Should send public events to the channels of the gamemode`() {
        addListeners()

        assertEquals(listOf("all", "osu"), send(false, listOf(Gamemode.osu)))
        assertEquals(listOf("all", "osu", "mania"), send(false, listOf(Gamemode.mania, Gamemode.osu)))
        assertEquals(listOf("all"), send(false, listOf(Gamemode.fruits)))
        assertEquals(listOf("all", "osu", "mania"), send(false, emptyList()))
    }

    @Test
    fun `Should send confidential events to the confidential channels of any gamemode`() {
        addListeners()

        assertEquals(listOf("secret", "secret-osu"), send(true, listOf(Gamemode.mania)))
        assertEquals(listOf("secret", "secret-osu"), send(true, emptyList()))
    }

    @Test
    fun `Should send an event`() {
        source.create(listener())
        discord.addChannel("1")

        send(false, emptyList())
        val embed = discord.messages.single().embed
        assertEquals("text", embed.getString("description"))
        assertEquals(3447003, embed.getInt("color"))
        assertEquals("https://a.ppy.sh/1", embed.getObject("thumbnail").getString("url"))
        assertEquals("Aiess", embed.getObject("footer").getString("text"))
        assertTrue(embed.getObject("footer").isNull("icon_url"))

        send(false, emptyList(), thumbnail = "", footer = EmbedFooter("", "https://a.ppy.sh/2"))
        assertFalse(
            discord.messages
                .single()
                .embed
                .hasKey("thumbnail"),
        )
        val footer =
            discord.messages
                .single()
                .embed
                .getObject("footer")
        assertEquals("", footer.getString("text"))
        assertEquals("https://a.ppy.sh/2", footer.getString("icon_url"))
    }

    @Test
    fun `Should send a beatmap event`() {
        source.create(listener())
        discord.addChannel("1")
        client.sendBeatmapUpdate("text", EmbedColor.RED, "7", User("99", "Editor", emptyList()), false, listOf(Gamemode.osu))

        val embed = discord.messages.single().embed
        assertEquals("text", embed.getString("description"))
        assertEquals(15158332, embed.getInt("color"))
        assertEquals("https://b.ppy.sh/thumb/7l.jpg", embed.getObject("thumbnail").getString("url"))
        assertEquals("Editor", embed.getObject("footer").getString("text"))
        assertEquals("https://a.ppy.sh/99", embed.getObject("footer").getString("icon_url"))

        discord.messages.clear()
        client.sendBeatmapUpdate("text", EmbedColor.RED, "7", null, false, emptyList())
        val footer =
            discord.messages
                .single()
                .embed
                .getObject("footer")
        assertEquals("", footer.getString("text"))
        assertFalse(footer.hasKey("icon_url"))
    }

    @Test
    fun `Should remove a listener of a channel that no longer exists`() {
        source.create(listener("gone"))
        source.create(listener("2"))
        discord.addChannel("2")

        assertEquals(listOf("2"), send(false, emptyList()))
        assertEquals(listOf(listener("2")), source.getListeners())
        assertEquals(listOf(listener("2")), source.list())
    }

    @Test
    fun `Should continue when a channel fails`() {
        source.create(listener("broken"))
        source.create(listener("2"))
        discord.addBrokenChannel("broken")
        discord.addChannel("2")

        assertEquals(listOf("2"), send(false, emptyList()))
        assertEquals(2, source.getListeners().size)
    }
}
