package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.LOGIN_FAILED_ICON
import nl.greaper.bnplanner.LOGIN_ICON
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import org.springframework.stereotype.Service

/** Tells the developer who logged in */
@Service
class AuthService(
    private val discordClient: DiscordClient?,
) {
    fun logLogin(
        user: User?,
        exception: Throwable? = null,
    ) {
        val loginMessage =
            when {
                exception != null -> {
                    buildString {
                        appendLine("**ERROR:** Unexpected error occurred while logging in")
                        append(exception.message)
                    }
                }

                user == null -> {
                    "**ERROR:** Could not find user while logging in"
                }

                else -> {
                    "**LOGIN:** ${user.username} logged in"
                }
            }

        val failed = loginMessage.startsWith("**ERROR")
        discordClient?.send(
            description = "${if (failed) LOGIN_FAILED_ICON else LOGIN_ICON} $loginMessage",
            color = if (failed) EmbedColor.RED else EmbedColor.GREEN,
            thumbnail = EmbedThumbnail(user?.osuId?.let { "https://a.ppy.sh/$it" } ?: ""),
            footer = EmbedFooter(user?.username ?: "", user?.osuId?.let { "https://a.ppy.sh/$it" }),
            confidential = true,
            gamemodes = emptyList(),
        )
    }
}
