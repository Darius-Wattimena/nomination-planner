package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.ADDED_NOMINATOR_ICON
import nl.greaper.bnplanner.CHANGE_BEATMAP_NOTE_ICON
import nl.greaper.bnplanner.CREATED_BEATMAP_ICON
import nl.greaper.bnplanner.DELETED_BEATMAP_ICON
import nl.greaper.bnplanner.REMOVED_NOMINATOR_ICON
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmap
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapNominator
import nl.greaper.bnplanner.model.beatmap.LegacyBeatmap
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import nl.greaper.bnplanner.util.Sleeper
import nl.greaper.bnplanner.util.findMainGamemodes
import nl.greaper.bnplanner.util.getEmojiIcon
import nl.greaper.bnplanner.util.hasAnyNomination
import nl.greaper.bnplanner.util.hasEnoughNominations
import nl.greaper.bnplanner.util.toReadableName
import org.springframework.stereotype.Service
import java.lang.StringBuilder
import java.time.Instant

@Service
class BeatmapService(
    private val dataSource: BeatmapDataSource,
    private val userService: UserService,
    private val osuHttpClient: OsuHttpClient,
    private val discordClient: DiscordClient?,
    private val sleeper: Sleeper = Sleeper.THREAD,
) {
    val log = KotlinLogging.logger { }

    fun findBeatmap(id: String): Beatmap? = dataSource.findById(id)

    fun findBeatmapsByNominator(nominatorId: String): List<Beatmap> {
        val filter =
            setupFilter(
                nominators = setOf(nominatorId),
                page = null,
            )

        return dataSource.findAll(filter)
    }

    fun findExposedBeatmap(
        osuApiToken: String,
        id: String,
    ): ExposedBeatmap? = dataSource.findById(id)?.toExposedBeatmap()

    fun deleteBeatmap(
        osuApiToken: String,
        osuId: String,
    ) {
        val databaseBeatmap = dataSource.findById(osuId) ?: return
        if (dataSource.deleteById(osuId)) {
            logBeatmapDelete(osuApiToken, databaseBeatmap)
        }
    }

    fun countBeatmaps(
        search: String?,
        artist: String?,
        title: String?,
        mapper: String?,
        status: Set<BeatmapStatus>,
        nominators: Set<String>,
        page: BeatmapPage,
        gamemodes: Set<Gamemode>,
        missingNominator: Set<Gamemode>,
    ): Int = dataSource.count(setupFilter(artist, title, mapper, status, nominators, page, gamemodes, missingNominator, search))

    fun findBeatmapsIds(
        search: String?,
        artist: String?,
        title: String?,
        mapper: String?,
        status: Set<BeatmapStatus>,
        nominators: Set<String>,
        page: BeatmapPage,
        gamemodes: Set<Gamemode>,
        missingNominator: Set<Gamemode>,
    ): Set<String> {
        val filter = setupFilter(artist, title, mapper, status, nominators, page, gamemodes, missingNominator, search)

        return dataSource
            .findAll(filter)
            .map { it.osuId }
            .toSet()
    }

    fun findBeatmaps(
        osuApiToken: String,
        search: String?,
        artist: String?,
        title: String?,
        mapper: String?,
        status: Set<BeatmapStatus>,
        nominators: Set<String>,
        page: BeatmapPage,
        pageNumber: Int,
        pageLimit: PageLimit,
        gamemodes: Set<Gamemode>,
        missingNominator: Set<Gamemode>,
    ): List<ExposedBeatmap> =
        dataSource
            .findAll(
                setupFilter(artist, title, mapper, status, nominators, page, gamemodes, missingNominator, search),
                pageNumber,
                pageLimit,
            ).mapNotNull { it.toExposedBeatmap() }

    fun addBeatmap(
        osuApiToken: String,
        input: NewBeatmap,
    ): ExposedBeatmap? {
        val addDate = Instant.now()

        val databaseBeatmap = dataSource.findById(input.osuId)

        // Only request a beatmap from the api if we don't have it
        if (databaseBeatmap != null) {
            return databaseBeatmap.toExposedBeatmap()
        }

        val parsedToken = osuApiToken.removePrefix("Bearer ")
        val osuBeatmap = osuHttpClient.findBeatmapWithId(parsedToken, input.osuId) ?: return null

        val preparedBeatmapGamemodes =
            input.gamemodes.map {
                BeatmapGamemode(
                    gamemode = it,
                    nominators =
                        listOf(
                            BeatmapNominator(MISSING_USER_ID, false),
                            BeatmapNominator(MISSING_USER_ID, false),
                        ),
                    isReady = false,
                )
            }

        val databaseUser = userService.findUserById(osuBeatmap.user_id)

        if (databaseUser == null || databaseUser.restricted == true) {
            // Sleep the thread if we need to find the user as well to avoid the call being blocked
            sleeper.sleep(1_000)
            val mapper = userService.forceFindUserById(parsedToken, osuBeatmap.user_id) ?: databaseUser

            if (mapper == null) {
                // A beatmap without a known mapper would never be shown
                log.warn { "Could not add beatmap as the mapper could not be found (osuId: ${input.osuId})" }
                return null
            }
        }

        val newBeatmap =
            Beatmap(
                osuId = input.osuId,
                artist = osuBeatmap.artist,
                title = osuBeatmap.title,
                note = "",
                mapper = osuBeatmap.creator,
                mapperId = osuBeatmap.user_id,
                status = BeatmapStatus.Pending,
                gamemodes = preparedBeatmapGamemodes,
                dateAdded = addDate,
                dateUpdated = addDate,
                dateRanked = null,
                mainGamemodes = osuBeatmap.findMainGamemodes(),
            )

        dataSource.insertOne(newBeatmap).also { logBeatmapAdded(parsedToken, newBeatmap) }

        return newBeatmap.toExposedBeatmap()
    }

    fun syncBeatmap(
        osuApiToken: String,
        beatmap: Beatmap,
    ) {
        // Don't sync a beatmap if it is already marked as ranked
        if (beatmap.status == BeatmapStatus.Ranked) {
            return
        }

        val parsedToken = osuApiToken.removePrefix("Bearer ")
        val osuBeatmap = osuHttpClient.findBeatmapWithId(parsedToken, beatmap.osuId)

        if (osuBeatmap == null) {
            log.warn { "Could not find beatmap when requesting osu api (osuId: ${beatmap.osuId})" }
            return
        }

        val nominators =
            osuBeatmap.currentNominations
                .map { currentNomination ->
                    val userId = currentNomination.userId.toString()
                    val user =
                        userService.findUserById(userId)
                            ?: userService.forceFindUserById(parsedToken, userId)

                    if (user == null) {
                        // Leaving out a nominator would reset their nomination
                        log.warn { "Could not sync beatmap as nominator $userId could not be found (osuId: ${beatmap.osuId})" }
                        return
                    }

                    val nominatedGamemodes = currentNomination.rulesets.map { ruleset -> Gamemode.valueOf(ruleset) }
                    nominatedGamemodes.map { user.osuId to it }
                }.flatten()
                .groupBy({ it.second }, { it.first })

        // Asking osu! took a while, so use what is stored by now
        dataSource.modify(beatmap.osuId) { current -> applySync(current, osuBeatmap, nominators) }
    }

    private fun applySync(
        beatmap: Beatmap,
        osuBeatmap: BeatmapSet,
        nominators: Map<Gamemode, List<String>>,
    ): Beatmap {
        val newStatus =
            when (osuBeatmap.ranked) {
                1, 2 -> {
                    BeatmapStatus.Ranked
                }

                3 -> {
                    BeatmapStatus.Qualified
                }

                else -> {
                    if (osuBeatmap.currentNominations.isNotEmpty()) {
                        BeatmapStatus.Nominated
                    } else {
                        if (beatmap.gamemodes.any { it.hasAnyNomination() }) {
                            BeatmapStatus.Disqualified.takeIf {
                                beatmap.hasEnoughNominations()
                            } ?: BeatmapStatus.Reset
                        } else {
                            // Don't update, if set on reset we don't want to change this
                            null
                        }
                    }
                }
            }

        val dateRanked =
            if (newStatus == BeatmapStatus.Ranked) {
                osuBeatmap.ranked_date ?: Instant.now()
            } else {
                null
            }

        val newGamemodes =
            nominators
                .filterKeys { gamemode ->
                    beatmap.gamemodes.none { beatmapGamemode -> beatmapGamemode.gamemode == gamemode }
                }.map { (newGamemode, newNominators) ->
                    val preparedNewNominators =
                        newNominators.map { nominatorId ->
                            BeatmapNominator(nominatorId, true)
                        }

                    val newGamemodeFirst = preparedNewNominators[0]
                    val newGamemodeSecond =
                        preparedNewNominators.getOrElse(1) {
                            BeatmapNominator(MISSING_USER_ID, false)
                        }

                    BeatmapGamemode(
                        newGamemode,
                        listOf(newGamemodeFirst, newGamemodeSecond),
                        false,
                    )
                }

        val updatedBeatmapGamemodes =
            beatmap.gamemodes.mapNotNull { beatmapGamemode ->
                val newNominators = nominators[beatmapGamemode.gamemode]

                // When we don't have any nominators of this gamemode it can either mean gamemode is removed or nominations are reset
                if (newNominators == null) {
                    val resetNominators = beatmapGamemode.nominators.map { it.copy(hasNominated = false) }

                    // When already ranked we can assume this gamemode got removed
                    if (newStatus == BeatmapStatus.Ranked) {
                        return@mapNotNull null
                    }

                    return@mapNotNull beatmapGamemode.copy(nominators = resetNominators)
                }

                val (currentFirst, currentSecond) = beatmapGamemode.nominators[0] to beatmapGamemode.nominators[1]
                val newFirst = newNominators[0]
                val newSecond = newNominators.getOrNull(1)

                val updatedNomiantorPair =
                    if (newSecond != null) {
                        // Both nominators can be updated
                        BeatmapNominator(newFirst, true) to BeatmapNominator(newSecond, true)
                    } else {
                        when (newFirst) {
                            // First nominator was updated
                            currentFirst.nominatorId -> {
                                currentFirst.copy(hasNominated = true) to currentSecond
                            }

                            // Second nominator was updated
                            currentSecond.nominatorId -> {
                                currentFirst to currentSecond.copy(hasNominated = true)
                            }

                            // New nominator, replace first as we don't know
                            else -> {
                                BeatmapNominator(newFirst, true) to currentSecond
                            }
                        }
                    }

                beatmapGamemode.copy(nominators = updatedNomiantorPair.toList())
            }

        return beatmap.copy(
            artist = osuBeatmap.artist,
            title = osuBeatmap.title,
            mapper = osuBeatmap.creator,
            status = newStatus ?: beatmap.status,
            gamemodes = updatedBeatmapGamemodes + newGamemodes,
            dateUpdated = osuBeatmap.last_updated,
            dateRanked = dateRanked,
            mainGamemodes = osuBeatmap.findMainGamemodes() ?: beatmap.mainGamemodes,
        )
    }

    fun setupFilter(
        artist: String? = null,
        title: String? = null,
        mapper: String? = null,
        status: Set<BeatmapStatus> = emptySet(),
        nominators: Set<String> = emptySet(),
        page: BeatmapPage?,
        gamemodes: Set<Gamemode> = emptySet(),
        missingNominator: Set<Gamemode> = emptySet(),
        search: String? = null,
    ): (Beatmap) -> Boolean {
        val statuses = page?.let { findStatuses(it, status) } ?: BeatmapStatus.entries.toSet()

        return { beatmap ->
            beatmap.status in statuses &&
                beatmap.artist.contains(artist.orEmpty(), true) &&
                beatmap.title.contains(title.orEmpty(), true) &&
                beatmap.mapper.contains(mapper.orEmpty(), true) &&
                listOf(beatmap.artist, beatmap.title, beatmap.mapper).any { it.contains(search.orEmpty(), true) } &&
                isEmptyOrAny(nominators, beatmap.gamemodes.flatMap { it.nominators }.map { it.nominatorId }) &&
                isEmptyOrAny(gamemodes, beatmap.gamemodes.map { it.gamemode }) &&
                isEmptyOrAny(missingNominator, beatmap.gamemodes.filter { it.isMissingNominator() }.map { it.gamemode })
        }
    }

    private fun <T> isEmptyOrAny(
        wanted: Set<T>,
        found: List<T>,
    ): Boolean = wanted.isEmpty() || found.any { it in wanted }

    private fun BeatmapGamemode.isMissingNominator(): Boolean = nominators.any { it.nominatorId == MISSING_USER_ID }

    /** The wanted statuses of a page, or all of them when there are none */
    private fun findStatuses(
        page: BeatmapPage,
        wanted: Set<BeatmapStatus>,
    ): Set<BeatmapStatus> {
        val ofPage =
            when (page) {
                BeatmapPage.PENDING -> BeatmapStatus.entries - BeatmapStatus.Ranked - BeatmapStatus.Graved
                BeatmapPage.RANKED -> listOf(BeatmapStatus.Ranked)
                BeatmapPage.GRAVEYARD -> listOf(BeatmapStatus.Graved)
            }.toSet()

        return ofPage.intersect(wanted).ifEmpty { ofPage }
    }

    @Deprecated("Endpoint has been removed, to be removed")
    fun importLegacyBeatmaps(legacyBeatmaps: List<LegacyBeatmap>) {
        val convertedBeatmaps = legacyBeatmaps.mapNotNull { convertLegacyBeatmapToBeatmap(it) }
        dataSource.insertMany(convertedBeatmaps)
    }

    fun updateBeatmapNote(
        osuApiToken: String,
        osuId: String,
        newNote: String,
    ): Boolean {
        val updatedBeatmap =
            dataSource.modify(osuId) { current ->
                current.copy(note = newNote, dateUpdated = Instant.now()).takeIf { current.note != newNote }
            }

        updatedBeatmap?.let { logBeatmapNoteChange(osuApiToken, it) }

        return findBeatmap(osuId) != null
    }

    fun updateBeatmapStatus(
        osuApiToken: String,
        osuId: String,
        newStatus: BeatmapStatus,
    ): Boolean {
        val updatedBeatmap =
            dataSource.modify(osuId) { current ->
                current.copy(status = newStatus, dateUpdated = Instant.now()).takeIf { current.status != newStatus }
            }

        updatedBeatmap?.let { logBeatmapStatusChange(osuApiToken, it) }

        return findBeatmap(osuId) != null
    }

    fun updateBeatmapNominator(
        osuApiToken: String,
        osuId: String,
        gamemode: Gamemode,
        oldNominator: String,
        newNominator: String,
    ): ExposedBeatmap? {
        // Log to all gamemodes, even the deleted ones
        var gamemodesBeforeUpdate = emptyList<Gamemode>()

        val updatedBeatmap =
            dataSource.modify(osuId) { current ->
                gamemodesBeforeUpdate = current.gamemodes.map { it.gamemode }
                replaceNominator(current, gamemode, oldNominator, newNominator)
            } ?: return null

        logUpdatedNominators(osuApiToken, updatedBeatmap, oldNominator, newNominator, gamemode, gamemodesBeforeUpdate)

        return updatedBeatmap.toExposedBeatmap()
    }

    /** Null when someone else already replaced the old nominator, or the new one already is part of the gamemode */
    private fun replaceNominator(
        beatmap: Beatmap,
        gamemode: Gamemode,
        oldNominator: String,
        newNominator: String,
    ): Beatmap? {
        // A gamemode that is about to be added only has missing nominators
        val nominatorIds =
            beatmap.gamemodes
                .filter { it.gamemode == gamemode }
                .flatMap { it.nominators }
                .map { it.nominatorId }
                .ifEmpty { listOf(MISSING_USER_ID) }

        if (oldNominator !in nominatorIds || (newNominator != MISSING_USER_ID && newNominator in nominatorIds)) {
            return null
        }

        return updateBeatmapGamemode(beatmap, gamemode) { updatingGamemode ->
            val (first, second) = updatingGamemode.nominators.let { it[0] to it[1] }
            val newNominators =
                if (first.nominatorId == oldNominator) {
                    listOf(BeatmapNominator(newNominator, false), second)
                } else {
                    listOf(first, BeatmapNominator(newNominator, false))
                }

            // Remove the gamemode when both nominators are missing and this is not the last gamemode of the beatmap set
            updatingGamemode
                .copy(nominators = newNominators)
                .takeUnless { beatmap.gamemodes.size > 1 && newNominators.all { it.nominatorId == MISSING_USER_ID } }
        }
    }

    fun updateBeatmapGamemode(
        beatmap: Beatmap,
        gamemode: Gamemode,
        new: (old: BeatmapGamemode) -> BeatmapGamemode?,
    ): Beatmap? {
        val updatingGamemode = beatmap.gamemodes.find { it.gamemode == gamemode }

        if (updatingGamemode == null) {
            val newBeatmapGamemode =
                BeatmapGamemode(
                    gamemode = gamemode,
                    nominators =
                        listOf(
                            BeatmapNominator(MISSING_USER_ID, false),
                            BeatmapNominator(MISSING_USER_ID, false),
                        ),
                    isReady = false,
                )
            return updateBeatmapGamemode(beatmap, newBeatmapGamemode, new)
        }

        return updateBeatmapGamemode(beatmap, updatingGamemode, new)
    }

    fun updateBeatmapGamemode(
        beatmap: Beatmap,
        updatingGamemode: BeatmapGamemode,
        new: (old: BeatmapGamemode) -> BeatmapGamemode?,
    ): Beatmap {
        val newGamemode = new(updatingGamemode)

        // Only take the updated gamemode if the new gamemode isn't null
        val updatedGamemodes =
            if (newGamemode == null) {
                beatmap.gamemodes - updatingGamemode
            } else {
                beatmap.gamemodes - updatingGamemode + newGamemode
            }

        return beatmap.copy(
            gamemodes = updatedGamemodes,
            dateUpdated = Instant.now(),
        )
    }

    fun logBeatmapAdded(
        osuApiToken: String,
        beatmap: Beatmap,
    ) {
        val editor = userService.getEditor(osuApiToken)
        log.info { "[CREATE] ${editor?.username} added (beatmap = ${beatmap.osuId})" }
        val gamemodes = beatmap.gamemodes.joinToString { it.gamemode.toReadableName() }

        val message =
            StringBuilder()
                .append("$CREATED_BEATMAP_ICON **Created**")
                .append("\nGamemodes: $gamemodes")
                .append("\n**[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**")
                .append("\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId})")

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.GREEN,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    fun logBeatmapDelete(
        osuApiToken: String,
        beatmap: Beatmap,
    ) {
        val editor = userService.getEditor(osuApiToken)
        log.info { "[DELETE] ${editor?.username} deleted (beatmap = ${beatmap.osuId})" }

        val message =
            StringBuilder()
                .append("$DELETED_BEATMAP_ICON **Deleted")
                .append("\n[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**")
                .append("\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId})")

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.RED,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    fun logBeatmapNoteChange(
        osuApiToken: String,
        beatmap: Beatmap,
    ) {
        val editor = userService.getEditor(osuApiToken)
        log.info { "[UPDATE] ${editor?.username} changed note (beatmap = ${beatmap.osuId})" }

        val noteText =
            if (beatmap.note.isBlank()) {
                "$CHANGE_BEATMAP_NOTE_ICON **Removed note**"
            } else {
                "$CHANGE_BEATMAP_NOTE_ICON **Updated note**\n```${beatmap.note.replace("""\n  +""".toRegex(), "\n")}```"
            }

        val message =
            StringBuilder()
                .append(noteText)
                .append("\n**[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**")
                .append("\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId})")

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.ORANGE,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    fun logBeatmapStatusChange(
        osuApiToken: String,
        beatmap: Beatmap,
    ) {
        val editor = userService.getEditor(osuApiToken)
        log.info { "[UPDATE] ${editor?.username} changed status to ${beatmap.status.name} (beatmap = ${beatmap.osuId})" }

        val message =
            StringBuilder()
                .append("${beatmap.status.getEmojiIcon()} **Updated status to ${beatmap.status.name}")
                .append("\n[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**")
                .append("\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId})")

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.ORANGE,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    internal fun getChangedNominatorText(
        newNominator: User?,
        oldNominator: User?,
        isMultipleGamemodes: Boolean? = null,
        gamemode: Gamemode? = null,
    ): String {
        val nominatorChangesText = StringBuilder("")

        // We don't have to create any changed nominator text when nothing changed in the first place
        if (newNominator?.osuId == oldNominator?.osuId) {
            return nominatorChangesText.toString()
        }

        if (newNominator != null && newNominator.osuId != MISSING_USER_ID) {
            nominatorChangesText.append(
                "$ADDED_NOMINATOR_ICON **Added [${newNominator.username}](https://osu.ppy.sh/users/${newNominator.osuId})**",
            )

            if (isMultipleGamemodes == true && gamemode != null) {
                nominatorChangesText.append(" [${gamemode.toReadableName()}]")
            }
        }

        if (oldNominator != null && oldNominator.osuId != MISSING_USER_ID) {
            // Add a \n if we added a nominator as well
            if (nominatorChangesText.toString() != "") {
                nominatorChangesText.append("\n")
            }

            nominatorChangesText.append(
                "$REMOVED_NOMINATOR_ICON **Removed [${oldNominator.username}](https://osu.ppy.sh/users/${oldNominator.osuId})**",
            )

            if (isMultipleGamemodes == true && gamemode != null) {
                nominatorChangesText.append(" [${gamemode.toReadableName()}]")
            }
        }

        return nominatorChangesText.toString()
    }

    fun logUpdatedNominators(
        osuApiToken: String,
        beatmap: Beatmap,
        oldNominatorId: String,
        newNominatorId: String,
        gamemode: Gamemode,
        gamemodesBeforeUpdate: List<Gamemode>,
    ) {
        val editor = userService.getEditor(osuApiToken)
        val oldNominator = userService.findUserById(oldNominatorId)
        val newNominator = userService.findUserById(newNominatorId)

        // Take the biggest list of gamemodes to publish
        // On removal of a gamemode we want to publish to the deleted gamemode as well.
        // On adding of a new gamemode we want to publish to the existing ane new gamemode.
        val gamemodesToMessage =
            if (beatmap.gamemodes.size > gamemodesBeforeUpdate.size) {
                beatmap.gamemodes.map { it.gamemode }
            } else {
                gamemodesBeforeUpdate
            }
        val isMultipleGamemodes = gamemodesToMessage.size > 1

        val nominatorChangesText = getChangedNominatorText(newNominator, oldNominator, isMultipleGamemodes, gamemode)
        log.info { "[UPDATE] ${editor?.username} changed nominators from $oldNominatorId to $newNominatorId (beatmap = ${beatmap.osuId})" }
        val beatmapGamemodeMessagePart = gamemodesToMessage.joinToString { it.toReadableName() }

        val message =
            StringBuilder()
                .append(nominatorChangesText)
                .append("\n**[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**")
                .append("\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId}) [$beatmapGamemodeMessagePart]")

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.BLUE,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = false,
            gamemodes = gamemodesToMessage,
        )
    }

    private fun convertLegacyBeatmapToBeatmap(legacyBeatmap: LegacyBeatmap): Beatmap? {
        val nominatorOne = legacyBeatmap.nominators.getOrNull(0)?.toString() ?: MISSING_USER_ID
        val nominatorTwo = legacyBeatmap.nominators.getOrNull(1)?.toString() ?: MISSING_USER_ID

        // This should never happen
        val beatmapStatus = BeatmapStatus.fromPriorityStatus(legacyBeatmap.status.toInt()) ?: return null

        val catchGamemode =
            BeatmapGamemode(
                gamemode = Gamemode.fruits,
                nominators =
                    listOf(
                        BeatmapNominator(nominatorOne, legacyBeatmap.nominatedByBNOne),
                        BeatmapNominator(nominatorTwo, legacyBeatmap.nominatedByBNTwo),
                    ),
                isReady = legacyBeatmap.nominatedByBNOne || legacyBeatmap.nominatedByBNTwo,
            )

        if (legacyBeatmap._id == 0L) {
            log.debug { "Found a beatmap with Id 0? ${legacyBeatmap.artist} - ${legacyBeatmap.title} by ${legacyBeatmap.mapper}" }
        }

        // TODO instead of using the legacy data, retrieve the beatmap from the osu api
        return Beatmap(
            osuId = legacyBeatmap._id.toString(),
            artist = legacyBeatmap.artist,
            title = legacyBeatmap.title,
            note = legacyBeatmap.note,
            mapper = legacyBeatmap.mapper,
            mapperId = legacyBeatmap.mapperId.toString(),
            status = beatmapStatus,
            gamemodes = listOf(catchGamemode),
            dateAdded = Instant.ofEpochSecond(legacyBeatmap.dateAdded),
            dateUpdated = Instant.ofEpochSecond(legacyBeatmap.dateUpdated),
            dateRanked = Instant.ofEpochSecond(legacyBeatmap.dateRanked),
        )
    }

    private fun Beatmap.toExposedBeatmap(): ExposedBeatmap? {
        val mapper = userService.findUserById(mapperId) ?: return null
        val preparedGamemodes =
            gamemodes.map { entry ->
                val gamemodeNominators =
                    entry.nominators.mapNotNull {
                        val nominatorUser = userService.findUserById(it.nominatorId)

                        if (nominatorUser != null) {
                            ExposedBeatmapNominator(
                                nominator = nominatorUser,
                                hasNominated = it.hasNominated,
                            )
                        } else {
                            null
                        }
                    }

                ExposedBeatmapGamemode(
                    gamemode = entry.gamemode,
                    nominators = gamemodeNominators,
                    isReady = entry.isReady,
                )
            }

        return ExposedBeatmap(
            osuId = this.osuId,
            artist = artist,
            title = title,
            note = note,
            mapper = mapper,
            status = status,
            gamemodes = preparedGamemodes,
            dateAdded = dateAdded,
            dateUpdated = dateUpdated,
            dateRanked = dateRanked,
            mainGamemodes = mainGamemodes,
        )
    }
}
