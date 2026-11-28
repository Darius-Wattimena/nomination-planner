package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.client.OsuRequestException
import nl.greaper.bnplanner.client.OsuWait
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
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
     *
     * @param logEventToDiscord false to only log it here, like for a login or every user of a user sync
     * @param wait how long to wait for osu!, gives no clear answer after that
     */
    fun forceFindUserById(
        osuApiToken: String,
        osuId: String,
        logEventToDiscord: Boolean = true,
        wait: OsuWait = OsuWait.BACKGROUND,
    ): User? {
        val osuUser =
            try {
                osuHttpClient.findUserWithId(osuApiToken, osuId, wait)
            } catch (ex: OsuRequestException) {
                log.error(ex) { "Unable to update user (osuId = $osuId)" }
                return null
            }

        if (osuUser == null) {
            // Should only end up here if the user is restricted or the provided id is invalid
            val restrictedUser = saveFromOsu(User(osuId, "RESTRICTED", emptyList(), restricted = true))
            log.info { "[CREATE] Added restricted user (osuId = $osuId)" }

            if (logEventToDiscord) {
                discordClient?.send(
                    description = "Could not find user with id $osuId, created restricted user",
                    color = EmbedColor.ORANGE,
                    thumbnail = EmbedThumbnail("https://a.ppy.sh/$osuId"),
                    footer = EmbedFooter("Nomination Planner"),
                    confidential = true,
                    gamemodes = listOf(),
                )
            }

            return restrictedUser
        }

        val newUser = saveFromOsu(convertOsuUserToUser(osuUser))

        if (!logEventToDiscord) {
            log.debug { "[CREATE] Added user ${newUser.username} (osuId = $osuId)" }
            return newUser
        }

        log.info { "[CREATE] Added user ${newUser.username} (osuId = $osuId)" }
        discordClient?.send(
            description = "Created user $osuId, with username: ${newUser.username}",
            color = EmbedColor.BLUE,
            thumbnail = EmbedThumbnail("https://a.ppy.sh/$osuId"),
            footer = EmbedFooter("Nomination Planner"),
            confidential = true,
            gamemodes = listOf(),
        )

        return newUser
    }

    /**
     * Looks up many users at once, quietly, like the nominators of the ranked history. Whoever osu! leaves out is restricted, or an
     * id it doesn't know.
     *
     * @return the users found or marked as restricted, empty when osu! gave no clear answer
     */
    fun forceFindUsersByIds(
        osuApiToken: String,
        osuIds: Collection<String>,
    ): List<User> {
        val found =
            try {
                osuHttpClient.findUsersWithIds(osuApiToken, osuIds).associateBy { it.id }
            } catch (ex: OsuRequestException) {
                log.error(ex) { "Unable to look up ${osuIds.size} users" }
                return emptyList()
            }

        return osuIds.map { osuId ->
            val osuUser = found[osuId]
            if (osuUser == null) {
                log.info { "[CREATE] Added restricted user (osuId = $osuId)" }
                saveFromOsu(User(osuId, "RESTRICTED", emptyList(), restricted = true))
            } else {
                saveFromOsu(convertOsuUserToUser(osuUser))
            }
        }
    }

    /**
     * osu! knows nothing about the modes someone chose themselves, which they may have changed while asking osu!, nor about who
     * nominated before
     */
    private fun saveFromOsu(user: User): User =
        dataSource.modifyUser(user.osuId) { current ->
            user.copy(interests = current?.interests, gamemodes = withFormer(current, user.gamemodes))
        }

    /**
     * The roles of the groups someone is in now, and Former in each gamemode they nominated in before without a role in it now: of a
     * group they left, or Former already
     */
    private fun withFormer(
        before: User?,
        groups: List<UserGamemode>,
    ): List<UserGamemode> {
        val now = groups.filter { it.role.canNominate }.map { it.gamemode }.toSet()
        val earlier =
            before
                ?.gamemodes
                .orEmpty()
                .filter { it.role.canNominate || it.role == Role.Former }
                .map { it.gamemode }
                .toSet()

        return groups + Gamemode.entries.filter { it in earlier && it !in now }.map { UserGamemode(it, Role.Former) }
    }

    /**
     * Gives Former to everyone who nominated a ranked beatmap in a gamemode they have no role in now, like after importing the
     * ranked history
     *
     * @return how many users changed
     */
    fun markFormerNominators(beatmaps: Collection<Beatmap>): Int {
        val nominated =
            beatmaps
                .filter { it.status == BeatmapStatus.Ranked }
                .flatMap { beatmap -> beatmap.gamemodes.flatMap { mode -> mode.nominators.map { it.nominatorId to mode.gamemode } } }
                .filter { (userId, _) -> userId != MISSING_USER_ID }
                .groupBy({ it.first }, { it.second })

        val changed =
            nominated.mapNotNull { (userId, modes) ->
                val user = dataSource.findUser(userId) ?: return@mapNotNull null
                val known =
                    user.gamemodes
                        .filter { it.role.canNominate || it.role == Role.Former }
                        .map { it.gamemode }
                        .toSet()
                val added = Gamemode.entries.filter { it in modes && it !in known }
                added.takeIf { it.isNotEmpty() }?.let {
                    user.copy(
                        gamemodes =
                            user.gamemodes + it.map { mode -> UserGamemode(mode, Role.Former) },
                    )
                }
            }
        // Only who is in a group now is shown by the planner, the thousands of others don't wake whoever waits for it
        val (shown, others) = changed.partition { user -> user.gamemodes.any { it.role.canNominate } }
        dataSource.saveAll(shown)
        dataSource.saveAll(others, forPlanner = false)
        log.info { "Marked ${changed.size} users as former nominators of the gamemodes they nominated ranked beatmaps of" }

        return changed.size
    }

    /**
     * Everyone who nominated before in a gamemode they have no role in now, by name
     *
     * @param gamemode only those who nominated this gamemode before, null for all of them, thousands after the ranked history
     */
    fun findFormerNominators(gamemode: Gamemode? = null): List<User> =
        dataSource
            .allUsers()
            .filter { user -> user.gamemodes.any { it.role == Role.Former && (gamemode == null || it.gamemode == gamemode) } }
            .sortedBy { it.username.lowercase() }

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

        // Like a user sync, only the roles of groups remain, and Former where someone no longer has a role
        val otherGroups = current.gamemodes.filter { it.role != role && it.role.canNominate }
        val updated =
            current.copy(
                username = event.userName ?: current.username,
                gamemodes = withFormer(current, otherGroups + Gamemode.entries.filter { it in after }.map { UserGamemode(it, role) }),
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
        val chosen = Gamemode.entries.filter { it in interests }.takeIf { it.isNotEmpty() }
        return dataSource.modifyUser(osuId) { current -> current?.copy(interests = chosen) }
    }

    /** Everyone the planner has in the BN, probationary BN or NAT */
    fun findUsersInGroups(): List<User> = dataSource.allUsers().filter { user -> user.gamemodes.any { it.role.canNominate } }

    fun findUserById(osuId: String): User? {
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
