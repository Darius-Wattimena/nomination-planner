package nl.greaper.bnplanner.client

import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.MessageEmbed
import nl.greaper.bnplanner.datasource.DiscordEventListenerDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.discord.DiscordEventListener
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedMessage
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.discord.getValue
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "discord", name = ["enabled"], havingValue = "true")
class DiscordBotClient(
    private val dataSource: DiscordEventListenerDataSource,
    private val jda: JDA,
) : DiscordClient {
    val log = KotlinLogging.logger { }

    override fun sendBeatmapUpdate(
        description: String,
        color: EmbedColor,
        beatmapId: String,
        editor: User?,
        confidential: Boolean,
        gamemodes: List<Gamemode>,
    ) = send(
        description = description,
        color = color,
        thumbnail = EmbedThumbnail("https://b.ppy.sh/thumb/${beatmapId}l.jpg"),
        footer = EmbedFooter(editor?.username ?: "", editor?.let { "https://a.ppy.sh/${it.osuId}" }),
        confidential = confidential,
        gamemodes = gamemodes,
    )

    override fun send(
        description: String,
        color: EmbedColor,
        thumbnail: EmbedThumbnail,
        footer: EmbedFooter,
        confidential: Boolean,
        gamemodes: List<Gamemode>,
    ) = send(
        EmbedMessage(
            description = description,
            color = color.getValue(),
            thumbnail = thumbnail,
            footer = footer,
        ),
        confidential,
        gamemodes,
    )

    private fun send(
        embedMessage: EmbedMessage,
        confidential: Boolean,
        gamemodes: List<Gamemode>,
    ) {
        val listeners = dataSource.getListeners()

        for (listener in listeners) {
            val isCorrectGamemode = listener.gamemode != null && gamemodes.contains(listener.gamemode)
            val isPublicMessage = ((gamemodes.isEmpty() || (listener.gamemode == null || isCorrectGamemode)) && !confidential)

            // Only send a message when relevant for the listener
            if ((confidential && listener.confidential) || (!listener.confidential && isPublicMessage)) {
                try {
                    deliver(listener, toEmbed(embedMessage))
                } catch (ex: Throwable) {
                    log.error(ex) { "Could not deliver discord message to server" }
                }
            }
        }
    }

    private fun deliver(
        listener: DiscordEventListener,
        embed: MessageEmbed,
    ) {
        val textChannel = jda.getTextChannelById(listener.channelId)

        if (textChannel != null) {
            textChannel.sendMessageEmbeds(embed).queue()
        } else if (jda.status == JDA.Status.CONNECTED && jda.getGuildById(listener.guildId) != null) {
            // The server is there, but the channel is gone
            log.info { "Removing the listener of channel ${listener.channelId}, it no longer exists (guildId = ${listener.guildId})" }
            dataSource.remove(listener)
        } else {
            // Not connected yet, or the server is unavailable for a while or removed the bot, which doesn't tell the channel is gone
            log.warn { "Skipping channel ${listener.channelId}, Discord doesn't know it right now (guildId = ${listener.guildId})" }
        }
    }

    /** Discord refuses a description that is too long, so it is cut off. The rest is short and the same every time. */
    private fun toEmbed(embedMessage: EmbedMessage): MessageEmbed {
        val builder =
            EmbedBuilder()
                .setDescription(cutOff(embedMessage.description, MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setColor(embedMessage.color)
                .setFooter(embedMessage.footer.text, embedMessage.footer.icon_url)

        if (embedMessage.thumbnail.url != "") {
            builder.setThumbnail(embedMessage.thumbnail.url)
        }

        return builder.build()
    }

    private fun cutOff(
        text: String,
        max: Int,
    ): String = if (text.length > max) text.take(max - 1) + "…" else text
}
