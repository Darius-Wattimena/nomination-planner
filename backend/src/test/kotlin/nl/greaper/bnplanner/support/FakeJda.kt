package nl.greaper.bnplanner.support

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.utils.data.DataObject
import net.dv8tion.jda.api.utils.data.SerializableData
import nl.greaper.bnplanner.config.DiscordConfig.DiscordBotHandler
import nl.greaper.bnplanner.datasource.DiscordEventListenerDataSource
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.util.EnumSet

/**
 * Everything the tests need to know about JDA, so this is the only test code to change when upgrading it.
 * What is sent to Discord is recorded as the JSON Discord gets, but only once it is actually queued.
 */
class FakeJda {
    data class Message(
        /** The channel, or null when answering a command */
        val channel: String?,
        val embed: DataObject,
    )

    val messages = mutableListOf<Message>()
    val replies = mutableListOf<String>()
    var deferred = 0
    var commands: DataObject? = null

    private val channels = mutableMapOf<String, TextChannel>()

    /** The servers the bot is in, which are available */
    val guilds = mutableSetOf("guild")
    var status = JDA.Status.CONNECTED

    val jda: JDA =
        Mockito.mock(JDA::class.java) {
            when (it.method.name) {
                "getTextChannelById" -> channels[it.arguments[0].toString()]
                "getGuildById" -> Mockito.mock(Guild::class.java).takeIf { _ -> it.arguments[0].toString() in guilds }
                "getStatus" -> status
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }

    /** Return an action doing something once it is queued */
    private fun action(
        invocation: InvocationOnMock,
        onQueue: () -> Unit,
    ): Any {
        val answer =
            Answer<Any?> {
                when {
                    it.method.name == "queue" -> {
                        onQueue()
                        null
                    }

                    it.method.returnType.isInstance(it.mock) -> {
                        it.mock
                    }

                    else -> {
                        Mockito.RETURNS_DEFAULTS.answer(it)
                    }
                }
            }
        return Mockito.mock(invocation.method.returnType, answer)
    }

    private fun sends(channel: String?) =
        Answer<Any?> {
            if (it.method.name == "sendMessageEmbeds") {
                val embed = it.arguments[0] as SerializableData
                action(it) { messages += Message(channel, embed.toData()) }
            } else {
                Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }

    fun addChannel(id: String) {
        channels[id] = Mockito.mock(TextChannel::class.java, sends(id))
    }

    fun addBrokenChannel(id: String) {
        channels[id] = Mockito.mock(TextChannel::class.java) { throw IllegalStateException("No permission") }
    }

    fun slashCommand(
        dataSource: DiscordEventListenerDataSource,
        name: String = "planner",
        subcommand: String?,
        gamemode: String? = null,
        admin: Boolean? = true,
        guildId: String? = "guild",
        channelId: String = "1",
        isFromGuild: Boolean = true,
        permissions: Boolean = true,
    ) {
        val guild =
            guildId?.let {
                Mockito.mock(Guild::class.java).also { guild ->
                    Mockito.`when`(guild.id).thenReturn(it)
                    Mockito.`when`(guild.name).thenReturn("Guild")
                }
            }
        val member =
            admin?.let {
                Mockito.mock(Member::class.java).also { member ->
                    val granted = if (it) EnumSet.of(Permission.ADMINISTRATOR) else EnumSet.of(Permission.MANAGE_CHANNEL)
                    Mockito.`when`(member.permissions).thenReturn(if (permissions) granted else null)
                }
            }
        val channel = Mockito.mock(MessageChannelUnion::class.java)
        Mockito.`when`(channel.id).thenReturn(channelId)
        val hook = Mockito.mock(InteractionHook::class.java, sends(null))
        val option =
            gamemode?.let {
                Mockito.mock(OptionMapping::class.java).also { option -> Mockito.`when`(option.asString).thenReturn(it) }
            }

        val event =
            Mockito.mock(SlashCommandInteractionEvent::class.java) {
                when (it.method.name) {
                    "isFromGuild" -> {
                        isFromGuild
                    }

                    "getName" -> {
                        name
                    }

                    "getSubcommandName" -> {
                        subcommand
                    }

                    "getGuild" -> {
                        guild
                    }

                    "getMember" -> {
                        member
                    }

                    "getChannel" -> {
                        channel
                    }

                    "getHook" -> {
                        hook
                    }

                    "getOption" -> {
                        option.takeIf { _ -> it.arguments[0] == "gamemode" }
                    }

                    "deferReply" -> {
                        action(it) { deferred++ }
                    }

                    "reply" -> {
                        val text = it.arguments[0] as String
                        action(it) { replies += text }
                    }

                    else -> {
                        Mockito.RETURNS_DEFAULTS.answer(it)
                    }
                }
            }
        DiscordBotHandler(dataSource).onSlashCommandInteraction(event)
    }

    fun guildReady(
        dataSource: DiscordEventListenerDataSource,
        guildId: String,
    ) {
        val guild =
            Mockito.mock(Guild::class.java) {
                when (it.method.name) {
                    "getId" -> {
                        guildId
                    }

                    "updateCommands" -> {
                        val registered = mutableListOf<CommandData>()
                        Mockito.mock(it.method.returnType) { call ->
                            when (call.method.name) {
                                "addCommands" -> {
                                    call.arguments.forEach { command -> registered += command as CommandData }
                                    call.mock
                                }

                                "queue" -> {
                                    commands = registered.single().toData()
                                    null
                                }

                                else -> {
                                    Mockito.RETURNS_DEFAULTS.answer(call)
                                }
                            }
                        }
                    }

                    else -> {
                        Mockito.RETURNS_DEFAULTS.answer(it)
                    }
                }
            }
        val event = Mockito.mock(GuildReadyEvent::class.java)
        Mockito.`when`(event.guild).thenReturn(guild)
        DiscordBotHandler(dataSource).onGuildReady(event)
    }
}
