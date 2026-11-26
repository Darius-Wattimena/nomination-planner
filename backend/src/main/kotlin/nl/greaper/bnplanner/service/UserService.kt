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
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.GroupEventType
import nl.greaper.bnplanner.model.osu.Me
import nl.greaper.bnplanner.model.osu.MeGroup
import nl.greaper.bnplanner.util.toReadableName
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
            return Gamemode.entries.toList()
        }

        // A gamemode osu! added later is left out, the planner can't show it
        return playmodes.mapNotNull { mode -> Gamemode.entries.find { it.name == mode.lowercase() } }
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
        // osu! knows nothing about the modes someone chose themselves
        val interests = dataSource.findUser(osuId)?.interests
        val osuUser =
            try {
                osuHttpClient.findUserWithId(osuApiToken, osuId)
            } catch (ex: OsuRequestException) {
                log.error(ex) { "Unable to update user (osuId = $osuId)" }
                return null
            }

        if (osuUser == null) {
            // Should only end up here if the user is restricted or the provided id is invalid
            val restrictedUser = User(osuId, "RESTRICTED", emptyList(), restricted = true, interests = interests)
            dataSource.saveUser(restrictedUser)
            log.info { "[CREATE] Added restricted user (osuId = $osuId)" }

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

        val newUser = convertOsuUserToUser(osuUser).copy(interests = interests)

        dataSource.saveUser(newUser)

        if (logEventToDiscord) {
            log.info { "[CREATE] Added user ${newUser.username} (osuId = $osuId)" }
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

    /**
     * Follow someone joining or leaving a group on osu!, or changing their gamemodes in it, the same way a user sync sees it afterwards
     *
     * @return the changed user, null when nothing changed
     */
    fun applyGroupEvent(event: GroupEvent): User? {
        val kind = event.kind ?: return null
        val userId = event.userId ?: return null

        if (event.groupId !in MeGroup.SupportedGroups) {
            return null
        }

        val role = Role.fromOsuId(event.groupId)
        val modes = event.playmodes.orEmpty().mapNotNull { mode -> Gamemode.entries.find { it.name == mode } }
        val known = findUserById(userId)
        val current = known ?: User(userId, event.userName ?: "Unknown", emptyList())
        val before =
            current.gamemodes
                .filter { it.role == role }
                .map { it.gamemode }
                .toSet()
        val inGroup =
            when (kind) {
                GroupEventType.USER_ADD -> modes.toSet()
                GroupEventType.USER_REMOVE -> emptySet()
                GroupEventType.USER_ADD_PLAYMODES -> before + modes
                GroupEventType.USER_REMOVE_PLAYMODES -> before - modes.toSet()
            }
        val isMember = kind != GroupEventType.USER_REMOVE
        // NAT members without a gamemode have access to all of them
        val after = if (role == Role.NominationAssessment && isMember && inGroup.isEmpty()) Gamemode.entries.toSet() else inGroup

        // Nothing to remember about someone unknown who isn't in the group
        if (known == null && after.isEmpty()) {
            return null
        }

        // Like a user sync, only the roles of groups remain
        val otherGroups = current.gamemodes.filter { it.role != role && it.role != Role.Mapper }
        val updated =
            current.copy(
                username = event.userName ?: current.username,
                gamemodes = otherGroups + Gamemode.entries.filter { it in after }.map { UserGamemode(it, role) },
                // Someone in a group can't be restricted
                restricted = current.restricted.takeUnless { isMember },
            )

        // The order of the gamemodes doesn't matter
        val inOrder = { user: User -> user.copy(gamemodes = user.gamemodes.sortedWith(compareBy({ it.role }, { it.gamemode }))) }
        if (known?.let(inOrder) == inOrder(updated)) {
            return null
        }

        dataSource.saveUser(updated)
        logGroupChange(updated, event, added = after - before, removed = before - after)

        return updated
    }

    private fun logGroupChange(
        user: User,
        event: GroupEvent,
        added: Set<Gamemode>,
        removed: Set<Gamemode>,
    ) {
        val group = event.groupName ?: event.groupId
        val changes =
            listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { "Added to $group [${it.joinToString { mode -> mode.toReadableName() }}]" },
                removed.takeIf { it.isNotEmpty() }?.let { "Removed from $group [${it.joinToString { mode -> mode.toReadableName() }}]" },
            ).ifEmpty { listOf("Updated") }

        log.info {
            "[OSU] ${event.type} ${user.username}: ${changes.joinToString()} (osuId = ${user.osuId}, event = ${event.id} at ${event.createdAt})"
        }

        discordClient?.send(
            description = "**[${user.username}](https://osu.ppy.sh/users/${user.osuId})**\n${changes.joinToString("\n")}",
            color = if (removed.isEmpty()) EmbedColor.GREEN else EmbedColor.RED,
            thumbnail = EmbedThumbnail("https://a.ppy.sh/${user.osuId}"),
            footer = EmbedFooter("osu!"),
            confidential = true,
            gamemodes = listOf(),
        )
    }

    /**
     * Save the modes a user is interested in, null when the user is not known
     *
     * These only decide what the website shows them, their roles decide what they may do.
     */
    fun saveInterests(
        osuId: String,
        interests: Set<Gamemode>,
    ): User? {
        val user = findUserById(osuId) ?: return null
        val chosen = Gamemode.entries.filter { it in interests }.takeIf { it.isNotEmpty() }
        return user.copy(interests = chosen).also { dataSource.saveUser(it) }
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
}
