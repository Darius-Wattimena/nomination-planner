package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.ADDED_NOMINATOR_ICON
import nl.greaper.bnplanner.CHANGE_BEATMAP_NOTE_ICON
import nl.greaper.bnplanner.CREATED_BEATMAP_ICON
import nl.greaper.bnplanner.DELETED_BEATMAP_ICON
import nl.greaper.bnplanner.REMOVED_NOMINATOR_ICON
import nl.greaper.bnplanner.auth.OsuLogin
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.client.OsuUnavailableException
import nl.greaper.bnplanner.client.OsuWait
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapFilter
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmap
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapEvent
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmapNominator
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
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
    private val events: BeatmapEventDataSource,
) {
    companion object {
        /** How much of the history of a beatmap is shown */
        const val HISTORY_SIZE = 50
    }

    private val log = KotlinLogging.logger { }

    /** How adding a beatmap went */
    sealed interface AddResult {
        data class Added(
            val beatmap: ExposedBeatmap,
        ) : AddResult

        data object AlreadyOnPlanner : AddResult

        data object UnknownToOsu : AddResult
    }

    fun findBeatmap(id: String): Beatmap? = dataSource.findById(id)

    /** On every page */
    fun findBeatmapsByNominator(nominatorId: String): List<Beatmap> =
        dataSource.findAll { beatmap -> beatmap.gamemodes.any { gamemode -> gamemode.nominators.any { it.nominatorId == nominatorId } } }

    fun findExposedBeatmap(id: String): ExposedBeatmap? = dataSource.findById(id)?.toExposedBeatmap()

    /** The newest of what happened to a beatmap, also of one that isn't on the planner (anymore) */
    fun findEvents(id: String): List<ExposedBeatmapEvent> =
        events.findNewest(id, HISTORY_SIZE).map { event ->
            ExposedBeatmapEvent(
                type = event.type,
                at = event.at,
                user = event.userId?.let { findUserOrUnknown(it) },
                gamemodes = event.gamemodes,
                nominator = event.nominatorId?.let { findUserOrUnknown(it) },
                oldNominator = event.oldNominatorId?.let { findUserOrUnknown(it) },
                status = event.status,
                osuEvent = event.osuEvent,
            )
        }

    private fun findUserOrUnknown(id: String): User = userService.findUserById(id) ?: User(id, "Unknown", emptyList())

    /** @return false when the beatmap is not on the planner */
    fun deleteBeatmap(
        login: OsuLogin,
        osuId: String,
    ): Boolean {
        val databaseBeatmap = dataSource.findById(osuId) ?: return false

        // Someone else may have deleted it in the meantime
        if (!dataSource.deleteById(osuId)) {
            return false
        }

        logBeatmapDelete(login, databaseBeatmap)
        return true
    }

    fun countBeatmaps(filter: BeatmapFilter): Int = dataSource.count(setupFilter(filter))

    fun findBeatmaps(
        filter: BeatmapFilter,
        pageNumber: Int,
        pageLimit: PageLimit,
    ): List<ExposedBeatmap> = dataSource.findAll(setupFilter(filter), pageNumber, pageLimit).map { it.toExposedBeatmap() }

    /** The ids of the beatmaps in progress with one of these statuses, or of all of them when there are none */
    fun findPendingIds(statuses: Set<BeatmapStatus>): Set<String> {
        val wanted = statuses.ifEmpty { BeatmapPage.PENDING.statuses }

        return dataSource
            .findAll { it.status in wanted }
            .map { it.osuId }
            .toSet()
    }

    fun addBeatmap(
        login: OsuLogin,
        input: NewBeatmap,
    ): AddResult {
        val addDate = Instant.now()

        // Only ask osu! for a beatmap that isn't on the planner yet
        if (dataSource.findById(input.osuId) != null) {
            return AddResult.AlreadyOnPlanner
        }

        // Someone is waiting for it
        val osuBeatmap = osuHttpClient.findBeatmapWithId(login.accessToken, input.osuId, OsuWait.REQUEST) ?: return AddResult.UnknownToOsu

        val preparedBeatmapGamemodes =
            input.gamemodes.map {
                BeatmapGamemode(
                    gamemode = it,
                    nominators =
                        listOf(
                            BeatmapNominator(MISSING_USER_ID, false),
                            BeatmapNominator(MISSING_USER_ID, false),
                        ),
                )
            }

        val databaseUser = userService.findUserById(osuBeatmap.user_id)

        if (databaseUser == null || databaseUser.restricted == true) {
            // Without a clear answer of osu!, the beatmap is shown with the mapper name osu! gave
            userService.forceFindUserById(login.accessToken, osuBeatmap.user_id, wait = OsuWait.REQUEST)
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

        // Asking osu! takes a while, someone else may have added it in the meantime
        if (dataSource.insertIfMissing(newBeatmap) != null) {
            return AddResult.AlreadyOnPlanner
        }

        events.insert(BeatmapEvent(newBeatmap.osuId, BeatmapEventType.Added, addDate, login.osuId, input.gamemodes))
        logBeatmapAdded(login, newBeatmap)
        return AddResult.Added(newBeatmap.toExposedBeatmap())
    }

    /**
     * Sync a beatmap right away, with the osu! token of whoever asked for it
     *
     * @return the beatmap as it is now, null when it is not on the planner
     * @throws OsuUnavailableException when osu! gave no clear answer in time
     */
    fun syncBeatmap(
        login: OsuLogin,
        osuId: String,
    ): ExposedBeatmap? {
        val beatmap = findBeatmap(osuId) ?: return null
        syncBeatmap(login.accessToken, beatmap, OsuWait.REQUEST, login.osuId)
        return findExposedBeatmap(osuId)
    }

    /**
     * @param by who asked for it, null for a sync in the background
     * @throws OsuUnavailableException when osu! gave no clear answer about the beatmap or one of its nominators
     */
    fun syncBeatmap(
        osuApiToken: String,
        beatmap: Beatmap,
        wait: OsuWait = OsuWait.BACKGROUND,
        by: String? = null,
    ) {
        // Don't sync a beatmap if it is already marked as ranked
        if (beatmap.status == BeatmapStatus.Ranked) {
            return
        }

        val osuBeatmap = osuHttpClient.findBeatmapWithId(osuApiToken, beatmap.osuId, wait)

        if (osuBeatmap == null) {
            log.warn { "Could not find beatmap when requesting osu api (osuId: ${beatmap.osuId})" }
            return
        }

        val nominators = findNominators(osuApiToken, osuBeatmap, beatmap.osuId, wait)

        // Asking osu! took a while, so use what is stored by now
        dataSource.modify(beatmap.osuId) { current ->
            applySync(current, osuBeatmap, nominators).also { recordSync(current, it, by) }
        }
    }

    /** A sync in the background is only part of the history when it changed something */
    private fun recordSync(
        before: Beatmap,
        after: Beatmap,
        by: String?,
    ) {
        if (by == null && before == after) {
            return
        }

        events.insert(
            BeatmapEvent(after.osuId, BeatmapEventType.Synced, Instant.now(), by, status = after.status.takeIf { it != before.status }),
        )
    }

    /** Who nominated which gamemode, asking osu! about the nominators the planner doesn't know yet */
    private fun findNominators(
        osuApiToken: String,
        osuBeatmap: BeatmapSet,
        osuId: String,
        wait: OsuWait,
    ): Map<Gamemode, List<String>> {
        val nominations =
            osuBeatmap.currentNominations.map { nomination ->
                val userId = nomination.userId.toString()
                val user =
                    userService.findUserById(userId)
                        ?: userService.forceFindUserById(osuApiToken, userId, wait = wait)
                        // Leaving out a nominator would reset their nomination
                        ?: throw OsuUnavailableException("Nominator $userId of beatmap $osuId could not be found", null)

                user.osuId to nomination.rulesets.mapNotNull { findGamemode(it, osuId) }
            }

        return nominations
            .flatMap { (userId, gamemodes) -> gamemodes.map { it to userId } }
            .groupBy({ it.first }, { it.second })
    }

    private fun applySync(
        beatmap: Beatmap,
        osuBeatmap: BeatmapSet,
        nominators: Map<Gamemode, List<String>>,
    ): Beatmap {
        val newStatus = syncedStatus(beatmap, osuBeatmap)

        return beatmap.copy(
            artist = osuBeatmap.artist,
            title = osuBeatmap.title,
            mapper = osuBeatmap.creator,
            status = newStatus ?: beatmap.status,
            gamemodes = syncedGamemodes(beatmap, newStatus, nominators) + addedGamemodes(beatmap, nominators),
            dateUpdated = osuBeatmap.last_updated,
            dateRanked = if (newStatus == BeatmapStatus.Ranked) osuBeatmap.ranked_date ?: Instant.now() else null,
            mainGamemodes = osuBeatmap.findMainGamemodes() ?: beatmap.mainGamemodes,
        )
    }

    /** Null when the status stays as it is */
    private fun syncedStatus(
        beatmap: Beatmap,
        osuBeatmap: BeatmapSet,
    ): BeatmapStatus? =
        when (osuBeatmap.ranked) {
            1, 2 -> {
                BeatmapStatus.Ranked
            }

            3 -> {
                BeatmapStatus.Qualified
            }

            // Loved, which a beatmap on the planner is never going to be ranked as
            4 -> {
                BeatmapStatus.Graved
            }

            else -> {
                when {
                    osuBeatmap.currentNominations.isNotEmpty() -> BeatmapStatus.Nominated

                    // A beatmap set on reset stays that way
                    beatmap.gamemodes.none { it.hasAnyNomination() } -> null

                    beatmap.hasEnoughNominations() -> BeatmapStatus.Disqualified

                    else -> BeatmapStatus.Reset
                }
            }
        }

    /** The gamemodes osu! has nominations of that are not on the planner yet */
    private fun addedGamemodes(
        beatmap: Beatmap,
        nominators: Map<Gamemode, List<String>>,
    ): List<BeatmapGamemode> =
        nominators
            .filterKeys { gamemode -> beatmap.gamemodes.none { it.gamemode == gamemode } }
            .map { (gamemode, nominatorIds) ->
                val nominated = nominatorIds.map { BeatmapNominator(it, true) }
                BeatmapGamemode(gamemode, listOf(nominated[0], nominated.getOrElse(1) { BeatmapNominator(MISSING_USER_ID, false) }))
            }

    /** The gamemodes on the planner, a gamemode without nominations is removed once ranked with nominations of others */
    private fun syncedGamemodes(
        beatmap: Beatmap,
        newStatus: BeatmapStatus?,
        nominators: Map<Gamemode, List<String>>,
    ): List<BeatmapGamemode> =
        beatmap.gamemodes.mapNotNull { beatmapGamemode ->
            val newNominators = nominators[beatmapGamemode.gamemode]

            when {
                newNominators != null -> beatmapGamemode.copy(nominators = mergeNominators(beatmapGamemode.nominators, newNominators))

                newStatus == BeatmapStatus.Ranked && nominators.isNotEmpty() -> null

                // Without any nominators of this gamemode its nominations were reset
                else -> beatmapGamemode.copy(nominators = beatmapGamemode.nominators.map { it.copy(hasNominated = false) })
            }
        }

    /** Keeps who is planned as far as osu! allows */
    private fun mergeNominators(
        current: List<BeatmapNominator>,
        newNominators: List<String>,
    ): List<BeatmapNominator> {
        val (currentFirst, currentSecond) = current[0] to current[1]
        val newFirst = newNominators[0]
        val newSecond = newNominators.getOrNull(1)

        return when {
            // Both nominators are known
            newSecond != null -> listOf(BeatmapNominator(newFirst, true), BeatmapNominator(newSecond, true))

            newFirst == currentFirst.nominatorId -> listOf(currentFirst.copy(hasNominated = true), currentSecond)

            newFirst == currentSecond.nominatorId -> listOf(currentFirst, currentSecond.copy(hasNominated = true))

            // A new nominator replaces the first, as we don't know
            else -> listOf(BeatmapNominator(newFirst, true), currentSecond)
        }
    }

    /**
     * Follow what happened on osu!, the same way a sync sees it afterwards. Only for beatmaps on the planner.
     *
     * @return the changed beatmap, null when it isn't on the planner or nothing changed
     * @throws IllegalStateException when the nominator is unknown and osu! gave no clear answer about them, to try again later
     */
    fun applyOsuEvent(
        osuApiToken: String,
        event: BeatmapsetEvent,
    ): Beatmap? {
        val kind = event.kind ?: return null
        val osuId = event.beatmapset?.id ?: return null
        findBeatmap(osuId) ?: return null

        val userId = event.userId?.toString()
        val gamemodes =
            event.comment
                ?.modes
                .orEmpty()
                .mapNotNull { findGamemode(it, osuId) }

        if (kind == BeatmapsetEventType.NOMINATE && userId != null) {
            // Like a sync, so the planner can show who nominated
            checkNotNull(userService.findUserById(userId) ?: userService.forceFindUserById(osuApiToken, userId)) {
                "Nominator $userId is unknown and osu! gave no clear answer (osuId: $osuId)"
            }
        }

        val changed = dataSource.modify(osuId) { current -> applyEvent(current, kind, userId, gamemodes, event.createdAt) } ?: return null

        log.info {
            "[OSU] ${kind.osuName} by ${userId ?: "osu!"} $gamemodes, now ${changed.status} (beatmap = $osuId, event = ${event.id})"
        }
        logOsuEvent(changed, kind, userId, gamemodes, event.createdAt)

        return changed
    }

    /** Null when nothing changes */
    private fun applyEvent(
        beatmap: Beatmap,
        kind: BeatmapsetEventType,
        userId: String?,
        gamemodes: List<Gamemode>,
        at: Instant,
    ): Beatmap? {
        // Like a sync, a ranked beatmap is done
        if (beatmap.status == BeatmapStatus.Ranked) {
            return null
        }

        val changed =
            when (kind) {
                BeatmapsetEventType.NOMINATE -> {
                    val nominated = gamemodes.fold(beatmap) { current, gamemode -> nominate(current, gamemode, userId ?: return null) }
                    nominated.copy(status = BeatmapStatus.Nominated)
                }

                BeatmapsetEventType.QUALIFY -> {
                    beatmap.copy(status = BeatmapStatus.Qualified)
                }

                BeatmapsetEventType.RANK -> {
                    // Like a sync, a gamemode without nominations was removed from the beatmap set
                    val nominated = beatmap.gamemodes.filter { it.hasAnyNomination() }
                    beatmap.copy(status = BeatmapStatus.Ranked, dateRanked = at, gamemodes = nominated.ifEmpty { beatmap.gamemodes })
                }

                BeatmapsetEventType.NOMINATION_RESET -> {
                    resetNominations(beatmap) { true }.copy(status = BeatmapStatus.Reset)
                }

                BeatmapsetEventType.DISQUALIFY -> {
                    resetNominations(beatmap) { true }.copy(status = BeatmapStatus.Disqualified)
                }

                BeatmapsetEventType.NOMINATION_RESET_RECEIVED -> {
                    resetNominations(beatmap) { it == userId }
                }
            }

        return changed.copy(dateUpdated = at).takeIf { changed != beatmap }
    }

    /** Keeps who is planned: their own place first, then a missing nominator, then someone who didn't nominate */
    private fun nominate(
        beatmap: Beatmap,
        gamemode: Gamemode,
        userId: String,
    ): Beatmap {
        val nominated = BeatmapNominator(userId, true)
        val existing = beatmap.gamemodes.find { it.gamemode == gamemode }

        if (existing == null) {
            return beatmap.copy(
                gamemodes =
                    beatmap.gamemodes + BeatmapGamemode(gamemode, listOf(nominated, BeatmapNominator(MISSING_USER_ID, false))),
            )
        }

        val nominators = existing.nominators
        val place =
            listOf<(BeatmapNominator) -> Boolean>({ it.nominatorId == userId }, { it.nominatorId == MISSING_USER_ID }, { !it.hasNominated })
                .map { wanted -> nominators.indexOfFirst(wanted) }
                .firstOrNull { it >= 0 }
                // Out of step with osu!, replace the first like a sync does
                ?: 0
        val updated = existing.copy(nominators = nominators.mapIndexed { index, nominator -> if (index == place) nominated else nominator })

        return beatmap.copy(gamemodes = beatmap.gamemodes.map { if (it.gamemode == gamemode) updated else it })
    }

    private fun resetNominations(
        beatmap: Beatmap,
        whose: (nominatorId: String) -> Boolean,
    ): Beatmap =
        beatmap.copy(
            gamemodes =
                beatmap.gamemodes.map { gamemode ->
                    gamemode.copy(nominators = gamemode.nominators.map { if (whose(it.nominatorId)) it.copy(hasNominated = false) else it })
                },
        )

    private fun logOsuEvent(
        beatmap: Beatmap,
        kind: BeatmapsetEventType,
        userId: String?,
        gamemodes: List<Gamemode>,
        at: Instant,
    ) {
        // Already told by the reset or disqualification itself
        if (kind == BeatmapsetEventType.NOMINATION_RESET_RECEIVED) {
            return
        }

        events.insert(
            BeatmapEvent(beatmap.osuId, BeatmapEventType.StatusChanged, at, userId, gamemodes, status = beatmap.status, osuEvent = kind),
        )

        val user = userId?.let { userService.findUserById(it) }
        val gamemodeText =
            gamemodes
                .joinToString { it.toReadableName() }
                .takeIf { it.isNotEmpty() }
                ?.let { " [$it]" }
                .orEmpty()
        val color =
            when (beatmap.status) {
                BeatmapStatus.Ranked -> EmbedColor.GREEN
                BeatmapStatus.Disqualified, BeatmapStatus.Reset -> EmbedColor.ORANGE
                else -> EmbedColor.BLUE
            }

        val message =
            StringBuilder()
                .append("${beatmap.status.getEmojiIcon()} **Updated status to ${beatmap.status.toReadableName()}**$gamemodeText")
                .append(describe(beatmap))

        discordClient?.send(
            description = message.toString(),
            color = color,
            thumbnail = EmbedThumbnail("https://b.ppy.sh/thumb/${beatmap.osuId}l.jpg"),
            footer = user?.let { EmbedFooter(it.username, "https://a.ppy.sh/${it.osuId}") } ?: EmbedFooter("osu!"),
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    private fun setupFilter(filter: BeatmapFilter): (Beatmap) -> Boolean {
        val search = filter.search.orEmpty()
        val nominators = filter.nominators.orEmpty()
        val gamemodes = filter.gamemodes.orEmpty()

        return { beatmap ->
            beatmap.status in filter.page.statuses &&
                listOf(beatmap.artist, beatmap.title, beatmap.mapper).any { it.contains(search, true) } &&
                isEmptyOrAny(nominators, beatmap.gamemodes.flatMap { it.nominators }.map { it.nominatorId }) &&
                isEmptyOrAny(gamemodes, beatmap.gamemodes.map { it.gamemode })
        }
    }

    private fun <T> isEmptyOrAny(
        wanted: Set<T>,
        found: List<T>,
    ): Boolean = wanted.isEmpty() || found.any { it in wanted }

    fun updateBeatmapNote(
        login: OsuLogin,
        osuId: String,
        newNote: String,
    ): Boolean {
        val updatedBeatmap =
            dataSource.modify(osuId) { current ->
                current.copy(note = newNote, dateUpdated = Instant.now()).takeIf { current.note != newNote }
            }

        updatedBeatmap?.let {
            events.insert(BeatmapEvent(osuId, BeatmapEventType.NoteChanged, it.dateUpdated, login.osuId))
            logBeatmapNoteChange(login, it)
        }

        return findBeatmap(osuId) != null
    }

    fun updateBeatmapStatus(
        login: OsuLogin,
        osuId: String,
        newStatus: BeatmapStatus,
    ): Boolean {
        val updatedBeatmap =
            dataSource.modify(osuId) { current ->
                current
                    .copy(
                        status = newStatus,
                        dateUpdated = Instant.now(),
                        dateRanked = if (newStatus == BeatmapStatus.Ranked) current.dateRanked ?: Instant.now() else null,
                    ).takeIf { current.status != newStatus }
            }

        updatedBeatmap?.let {
            events.insert(BeatmapEvent(osuId, BeatmapEventType.StatusChanged, it.dateUpdated, login.osuId, status = newStatus))
            logBeatmapStatusChange(login, it)
        }

        return findBeatmap(osuId) != null
    }

    fun updateBeatmapNominator(
        login: OsuLogin,
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

        nominatorEvents(
            updatedBeatmap,
            login.osuId,
            gamemode,
            oldNominator,
            newNominator,
            gamemodesBeforeUpdate,
        ).forEach { events.insert(it) }
        logUpdatedNominators(login, updatedBeatmap, oldNominator, newNominator, gamemode, gamemodesBeforeUpdate)

        return updatedBeatmap.toExposedBeatmap()
    }

    /** What changing a nominator did, in the order it happened */
    private fun nominatorEvents(
        beatmap: Beatmap,
        by: String,
        gamemode: Gamemode,
        oldNominator: String,
        newNominator: String,
        gamemodesBefore: List<Gamemode>,
    ): List<BeatmapEvent> {
        fun event(
            type: BeatmapEventType,
            nominatorId: String? = null,
            oldNominatorId: String? = null,
        ) = BeatmapEvent(beatmap.osuId, type, beatmap.dateUpdated, by, listOf(gamemode), nominatorId, oldNominatorId)

        val nominator =
            when {
                // Only an open spot that stays open, which changes nothing
                oldNominator == newNominator -> null

                oldNominator == MISSING_USER_ID -> event(BeatmapEventType.NominatorJoined, newNominator)

                newNominator == MISSING_USER_ID -> event(BeatmapEventType.NominatorLeft, oldNominator)

                else -> event(BeatmapEventType.NominatorReplaced, newNominator, oldNominator)
            }

        return listOfNotNull(
            event(BeatmapEventType.ModeAdded).takeIf { gamemode !in gamemodesBefore },
            nominator,
            event(BeatmapEventType.ModeRemoved).takeIf { beatmap.gamemodes.none { it.gamemode == gamemode } },
        )
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

    /** A gamemode that is not part of the beatmap yet is added, with missing nominators. Null removes the gamemode. */
    private fun updateBeatmapGamemode(
        beatmap: Beatmap,
        gamemode: Gamemode,
        new: (old: BeatmapGamemode) -> BeatmapGamemode?,
    ): Beatmap {
        val updatingGamemode =
            beatmap.gamemodes.find { it.gamemode == gamemode }
                ?: BeatmapGamemode(gamemode, listOf(BeatmapNominator(MISSING_USER_ID, false), BeatmapNominator(MISSING_USER_ID, false)))
        val newGamemode = new(updatingGamemode)

        // The updated gamemode moves to the end
        val otherGamemodes = beatmap.gamemodes - updatingGamemode

        return beatmap.copy(
            gamemodes = if (newGamemode == null) otherGamemodes else otherGamemodes + newGamemode,
            dateUpdated = Instant.now(),
        )
    }

    /** The beatmap and its mapper, as shown below every message about it */
    private fun describe(beatmap: Beatmap): String =
        "\n**[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})**" +
            "\nMapped by [${beatmap.mapper}](https://osu.ppy.sh/users/${beatmap.mapperId})"

    private fun logBeatmapAdded(
        login: OsuLogin,
        beatmap: Beatmap,
    ) {
        val editor = userService.findUserById(login.osuId)
        log.info { "[CREATE] ${editor?.username} added (beatmap = ${beatmap.osuId})" }
        val gamemodes = beatmap.gamemodes.joinToString { it.gamemode.toReadableName() }

        val message =
            StringBuilder()
                .append("$CREATED_BEATMAP_ICON **Created**")
                .append("\nGame modes: $gamemodes")
                .append(describe(beatmap))

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.GREEN,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    private fun logBeatmapDelete(
        login: OsuLogin,
        beatmap: Beatmap,
    ) {
        val editor = userService.findUserById(login.osuId)
        log.info { "[DELETE] ${editor?.username} deleted (beatmap = ${beatmap.osuId})" }

        val message =
            StringBuilder()
                .append("$DELETED_BEATMAP_ICON **Deleted**")
                .append(describe(beatmap))

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.RED,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    private fun logBeatmapNoteChange(
        login: OsuLogin,
        beatmap: Beatmap,
    ) {
        val editor = userService.findUserById(login.osuId)
        log.info { "[UPDATE] ${editor?.username} changed note (beatmap = ${beatmap.osuId})" }

        val noteText =
            if (beatmap.note.isBlank()) {
                "$CHANGE_BEATMAP_NOTE_ICON **Removed note**"
            } else {
                // A ``` in the note itself would end the code block, a zero width space in between keeps it as text
                val note = beatmap.note.replace("""\n  +""".toRegex(), "\n").replace("```", "`\u200B`\u200B`")
                "$CHANGE_BEATMAP_NOTE_ICON **Updated note**\n```$note```"
            }

        val message =
            StringBuilder()
                .append(noteText)
                .append(describe(beatmap))

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.ORANGE,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = true,
            gamemodes = beatmap.gamemodes.map { it.gamemode },
        )
    }

    private fun logBeatmapStatusChange(
        login: OsuLogin,
        beatmap: Beatmap,
    ) {
        val editor = userService.findUserById(login.osuId)
        log.info { "[UPDATE] ${editor?.username} changed status to ${beatmap.status.name} (beatmap = ${beatmap.osuId})" }

        val message =
            StringBuilder()
                .append("${beatmap.status.getEmojiIcon()} **Updated status to ${beatmap.status.toReadableName()}**")
                .append(describe(beatmap))

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

    private fun logUpdatedNominators(
        login: OsuLogin,
        beatmap: Beatmap,
        oldNominatorId: String,
        newNominatorId: String,
        gamemode: Gamemode,
        gamemodesBeforeUpdate: List<Gamemode>,
    ) {
        val editor = userService.findUserById(login.osuId)
        val oldNominator = userService.findUserById(oldNominatorId)
        val newNominator = userService.findUserById(newNominatorId)

        // Take the biggest list of gamemodes to publish
        // On removal of a gamemode we want to publish to the deleted gamemode as well.
        // On adding of a new gamemode we want to publish to the existing and new game modes.
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
                .append("\nGame modes: $beatmapGamemodeMessagePart")
                .append(describe(beatmap))

        discordClient?.sendBeatmapUpdate(
            description = message.toString(),
            color = EmbedColor.BLUE,
            beatmapId = beatmap.osuId,
            editor = editor,
            confidential = false,
            gamemodes = gamemodesToMessage,
        )
    }

    /** A gamemode osu! added later is left out, the planner can't show it */
    private fun findGamemode(
        ruleset: String,
        osuId: String,
    ): Gamemode? {
        val gamemode = Gamemode.entries.find { it.name == ruleset }
        if (gamemode == null) {
            log.warn { "Unknown gamemode $ruleset nominated (osuId: $osuId)" }
        }
        return gamemode
    }

    private fun Beatmap.toExposedBeatmap(): ExposedBeatmap {
        // Shown with the name osu! gave, until the planner knows the mapper
        val mapper = userService.findUserById(mapperId) ?: User(mapperId, this.mapper, emptyList())
        val preparedGamemodes =
            gamemodes.map { entry ->
                val gamemodeNominators =
                    entry.nominators.map {
                        // Keeps their place, a gamemode always has two nominators
                        ExposedBeatmapNominator(nominator = findUserOrUnknown(it.nominatorId), hasNominated = it.hasNominated)
                    }

                ExposedBeatmapGamemode(
                    gamemode = entry.gamemode,
                    nominators = gamemodeNominators,
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
