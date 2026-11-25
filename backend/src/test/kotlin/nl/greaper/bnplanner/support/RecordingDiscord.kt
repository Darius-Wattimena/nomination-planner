package nl.greaper.bnplanner.support

import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import org.junit.jupiter.api.Assertions.assertEquals

class RecordingDiscord : DiscordClient {
    data class Sent(
        val description: String,
        val color: EmbedColor,
        val confidential: Boolean,
        val gamemodes: List<Gamemode>,
        val thumbnail: EmbedThumbnail? = null,
        val footer: EmbedFooter? = null,
        val beatmapId: String? = null,
        val editor: User? = null,
    )

    val sent = mutableListOf<Sent>()

    fun single(): Sent {
        assertEquals(1, sent.size, "Expected one message but got $sent")
        return sent.single()
    }

    override fun sendBeatmapUpdate(
        description: String,
        color: EmbedColor,
        beatmapId: String,
        editor: User?,
        confidential: Boolean,
        gamemodes: List<Gamemode>,
    ) {
        sent += Sent(description, color, confidential, gamemodes, beatmapId = beatmapId, editor = editor)
    }

    override fun send(
        description: String,
        color: EmbedColor,
        thumbnail: EmbedThumbnail,
        footer: EmbedFooter,
        confidential: Boolean,
        gamemodes: List<Gamemode>,
    ) {
        sent += Sent(description, color, confidential, gamemodes, thumbnail, footer)
    }
}
