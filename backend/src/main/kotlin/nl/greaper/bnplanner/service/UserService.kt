package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.client.OsuRequestException
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.Me
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.util.parseJwtToken
import org.springframework.stereotype.Service

@Service
class UserService(
    private val dataSource: UserDataSource,
    private val osuHttpClient: OsuHttpClient,
    private val discordClient: DiscordClient?,
) {
    companion object {
        const val MISSING_USER_ID = "0"
    }

    private val log = KotlinLogging.logger { }

    private fun convertOsuUserToUser(osuUser: Me): User =
        User(
            osuId = osuUser.id,
            username = osuUser.username,
            gamemodes =
                osuUser.groups
                    ?.mapNotNull { osuGroup ->
                        if (MeGroup.SupportedGroups.contains(osuGroup.id)) {
                            findGamemodes(osuGroup).map { gamemode ->
                                UserGamemode(
                                    gamemode = gamemode,
                                    role = Role.fromOsuId(osuGroup.id),
                                )
                            }
                        } else {
                            // Unsupported usergroup
                            null
                        }
                    }?.flatten() ?: emptyList(),
        )

    private fun findGamemodes(osuGroup: MeGroup): List<Gamemode> {
        val playmodes = osuGroup.playmodes.orEmpty()

        // NAT members without a gamemode have access to all of them
        if (osuGroup.id == MeGroup.NAT && playmodes.isEmpty()) {
            return Gamemode.values().toList()
        }

        return playmodes.map { Gamemode.valueOf(it.lowercase()) }
    }

    /**
     * Find an osu user via the API and save them in the database for later use
     *
     * Returns null, leaving what we know about the user as is, when osu! gave no clear answer
     */
    fun forceFindUserById(
        osuApiToken: String,
        osuId: String,
        logEventToDiscord: Boolean = true,
    ): User? {
        val editor = getEditor(osuApiToken)
        val osuUser =
            try {
                osuHttpClient.findUserWithId(osuApiToken, osuId)
            } catch (ex: OsuRequestException) {
                log.error(ex) { "Unable to update user (osuId = $osuId)" }
                return null
            }

        if (osuUser == null) {
            // Should only end up here if the user is restricted or the provided id is invalid
            val restrictedUser = User(osuId, "RESTRICTED", emptyList(), restricted = true)
            dataSource.saveUser(restrictedUser)
            log.info { "[CREATE] ${editor?.username} added restricted user (osuId = $osuId)" }

            discordClient?.send(
                description = "Could not find user with id $osuId, created restricted user",
                color = EmbedColor.ORANGE,
                thumbnail = EmbedThumbnail("https://a.ppy.sh/$osuId"),
                footer = EmbedFooter("Nomination Planner"),
                confidential = true,
                gamemodes = listOf(),
            )

            return restrictedUser
        }

        val newUser = convertOsuUserToUser(osuUser)

        dataSource.saveUser(newUser)

        if (logEventToDiscord) {
            log.info { "[CREATE] ${editor?.username} added user ${newUser.username} (osuId = $osuId)" }
            discordClient?.send(
                description = "Created user $osuId, with username: ${newUser.username}",
                color = EmbedColor.BLUE,
                thumbnail = EmbedThumbnail("https://a.ppy.sh/$osuId"),
                footer = EmbedFooter("Nomination Planner"),
                confidential = true,
                gamemodes = listOf(),
            )
        }

        return newUser
    }

    fun findUserById(osuId: String): User? {
        if (osuId == "-1" || osuId == "-2") return null

        if (osuId == MISSING_USER_ID) {
            return User(
                MISSING_USER_ID,
                "None",
                gamemodes = emptyList(),
            )
        }

        return dataSource.findUser(osuId)
    }

    fun getEditorId(osuApiToken: String): String? {
        val claims = parseJwtToken(osuApiToken) ?: return null

        return claims.subject
    }

    fun getEditor(osuApiToken: String): User? {
        val editorId = getEditorId(osuApiToken) ?: return null
        return findUserById(editorId)
    }
}
