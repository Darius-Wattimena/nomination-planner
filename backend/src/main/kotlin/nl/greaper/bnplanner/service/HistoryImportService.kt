package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.datasource.HistoryImportDataSource
import nl.greaper.bnplanner.datasource.HistoryImportDataSource.NominationEvent
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.BeatmapsetEvent
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.util.Sleeper
import nl.greaper.bnplanner.util.findMainGamemodes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Imports every ranked beatmap of osu! with its nominators, meant to run once on a developer's own machine, whose export is imported
 * on the servers afterwards. First it reads every nomination, reset and disqualification osu! kept since 2008, 50 at a time, which
 * tell who nominated most ranked beatmaps. Only when those don't add up is osu! asked about a beatmap set on its own. It continues
 * where it stopped after a restart. Afterwards it works out who nominated which gamemode of the old beatmaps with several gamemodes.
 */
@Service
class HistoryImportService(
    private val osuClient: OsuHttpClient,
    private val osuService: OsuService,
    private val userService: UserService,
    private val users: UserDataSource,
    private val beatmaps: BeatmapDataSource,
    private val events: BeatmapEventDataSource,
    private val progress: HistoryImportDataSource,
    private val roleHistory: RoleHistoryService,
    @Value("\${osu.history-import.per-minute:60}") perMinute: Int = 60,
    private val sleeper: Sleeper = Sleeper.THREAD,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) {
    private val log = KotlinLogging.logger { }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "history-import").apply { isDaemon = true } }
    private val interval = 60_000L / perMinute.coerceAtLeast(1)
    private var lastCall = 0L

    enum class Phase {
        Roles,
        Nominations,
        Beatmaps,
        Modes,
    }

    data class Status(
        val running: Boolean,
        val phase: Phase? = null,
        /** Ranked beatmap sets of osu! seen so far, of all runs */
        val seen: Int = 0,
        val imported: Int = 0,
        /** The last day of which every nomination was read */
        val nominationsReached: LocalDate? = null,
        /** Beatmap sets osu! was asked about on their own, as their nominations didn't add up */
        val askedAbout: Int = 0,
        /** Beatmap sets of which the nominations were compared with what osu! tells of the beatmap set itself */
        val checked: Int = 0,
        /** Those where the two didn't agree */
        val disagreed: Int = 0,
        /** The ranked date of the last beatmap set seen, which shows how far it got */
        val reached: Instant? = null,
        /** Every ranked beatmap of osu! was seen */
        val done: Boolean = false,
        /** Beatmaps of which the gamemodes of the nominators were worked out */
        val guessed: Int = 0,
        val failed: String? = null,
    )

    @Volatile
    private var status: Status =
        progress.findProgress().let {
            Status(
                running = false,
                seen = it.seen,
                imported = it.imported,
                nominationsReached = it.nominationsFrom?.minusDays(1),
                askedAbout = it.askedAbout,
                checked = it.checked,
                disagreed = it.disagreed,
                done = it.done,
            )
        }

    @Volatile
    private var stopping = false

    /** The nominators osu! was asked about since the start of the backend */
    private val lookedUp = ConcurrentHashMap.newKeySet<String>()

    /** Pages of one search of osu!, which stops after 10,000 results. Only tests change it. */
    internal var pagesPerSearch = 150

    /** The first day of which the nominations are read, before the first ranked beatmap. Only tests change it. */
    internal var nominationsSince: LocalDate = LocalDate.of(2007, 10, 1)

    /** One in so many beatmap sets told by their nominations is also asked about on its own, to see whether those agree */
    internal var checkEvery = 100

    /** Beatmap sets told by their nominations since the start of the backend */
    private var told = 0

    fun findStatus(): Status = status

    /** @return false when it is already running */
    @Synchronized
    fun start(): Boolean {
        if (status.running) {
            return false
        }

        stopping = false
        status = status.copy(running = true, failed = null)
        executor.execute { run { importAll() } }
        return true
    }

    /** Works out the gamemodes again, like after correcting some by hand. @return false when something is already running */
    @Synchronized
    fun startModes(): Boolean {
        if (status.running) {
            return false
        }

        stopping = false
        status = status.copy(running = true, failed = null)
        executor.execute { run { guessModes() } }
        return true
    }

    /** Stops after the call to osu! it is busy with, it continues there when started again */
    fun stop() {
        stopping = true
    }

    /** Waits until it is done, only for tests */
    internal fun finish() {
        executor.submit {}.get()
    }

    private fun run(work: () -> Unit) {
        try {
            work()
            status = status.copy(running = false, phase = null)
        } catch (ex: Exception) {
            log.error(ex) { "The import of the ranked history stopped, it continues there when started again" }
            status = status.copy(running = false, phase = null, failed = "Stopped: ${ex.message}")
        }
    }

    private fun importAll() {
        status = status.copy(phase = Phase.Roles)
        pace()
        roleHistory.importHistory()
        val names = roleHistory.findAll().sortedBy { it.at }.associate { it.userId to it.username }

        readNominations()
        if (stopping) {
            return
        }

        status = status.copy(phase = Phase.Beatmaps)
        var current = progress.findProgress()

        while (!current.done && !stopping) {
            pace()
            val page = osuClient.searchRankedBeatmapsets(token(), current.since, current.cursor)
            val prepared = mutableListOf<Prepared>()

            for (found in page.beatmapsets) {
                if (stopping) {
                    // The page is read again, what was imported of it is skipped
                    return
                }
                prepare(found)?.let { prepared += it }
                status = status.copy(reached = found.ranked_date)
            }

            lookUpNominators(prepared.flatMap { it.nominations }.map { it.userId.toString() }.distinct(), names)
            val imported = current.imported + prepared.count { insert(it) }

            val lastDay =
                page.beatmapsets
                    .lastOrNull()
                    ?.ranked_date
                    ?.atZone(ZoneOffset.UTC)
                    ?.toLocalDate()
            // A new search from the last day reached, before osu! stops at 10,000 results. Who was seen of that day is skipped.
            val restart = current.pages + 1 >= pagesPerSearch && page.cursorString != null && lastDay != null && lastDay != current.since
            current =
                current.copy(
                    since = if (restart) lastDay else current.since,
                    cursor = if (restart) null else page.cursorString,
                    pages = if (restart) 0 else current.pages + 1,
                    done = page.cursorString == null || page.beatmapsets.isEmpty(),
                    seen = current.seen + page.beatmapsets.size,
                    imported = imported,
                    askedAbout = current.askedAbout + prepared.count { it.askedAbout },
                    checked = current.checked + prepared.count { it.checked },
                    disagreed = current.disagreed + prepared.count { it.disagreed },
                )
            progress.saveProgress(current)
            status =
                status.copy(
                    seen = current.seen,
                    imported = current.imported,
                    askedAbout = current.askedAbout,
                    checked = current.checked,
                    disagreed = current.disagreed,
                    done = current.done,
                )
        }

        if (current.done && !stopping) {
            guessModes()
        }
    }

    /** Reads every nomination, reset and disqualification of osu!, a month at a time, until today */
    private fun readNominations() {
        var current = progress.findProgress()
        if (current.nominationsDone || current.done) {
            return
        }

        status = status.copy(phase = Phase.Nominations)
        var from = current.nominationsFrom ?: nominationsSince
        val end = today()

        while (!from.isAfter(end)) {
            val until = minOf(from.withDayOfMonth(from.lengthOfMonth()), end)
            for (type in NOMINATION_TYPES) {
                var page = 1
                do {
                    if (stopping) {
                        // The month is read again
                        return
                    }
                    check(page <= MAX_PAGES_PER_MONTH) { "osu! gave more than $MAX_PAGES_PER_MONTH pages of ${type.osuName} from $from" }
                    val found = retried { osuClient.findNominationHistory(token(), from, until, type, page).events }
                    progress.saveNominations(found.mapNotNull { toNominationEvent(it) })
                    page++
                } while (found.size >= OsuHttpClient.EVENTS_PER_PAGE)
            }

            from = until.plusDays(1)
            current = current.copy(nominationsFrom = from)
            progress.saveProgress(current)
            status = status.copy(nominationsReached = until)
        }

        progress.saveProgress(current.copy(nominationsDone = true))
    }

    /**
     * Asks osu! again after a call that failed on the way or on the side of osu!, like one that took too long, as a run takes an hour
     * or more. An answer it can't read stays the same, so that stops it at once.
     */
    private fun <T> retried(call: () -> T): T {
        var attempt = 1
        while (true) {
            pace()
            try {
                return call()
            } catch (ex: RestClientException) {
                if (attempt >= ATTEMPTS || stopping) {
                    throw ex
                }
                log.warn { "Asking osu! again after: ${ex.message}" }
                attempt++
            }
        }
    }

    private fun toNominationEvent(event: BeatmapsetEvent): NominationEvent? {
        val kind =
            when (event.kind) {
                BeatmapsetEventType.NOMINATE -> NominationEvent.Kind.Nominate
                BeatmapsetEventType.NOMINATION_RESET, BeatmapsetEventType.DISQUALIFY -> NominationEvent.Kind.Reset
                else -> return null
            }
        // Of a deleted beatmap set
        val beatmapsetId = event.beatmapset?.id ?: return null

        return NominationEvent(event.id, beatmapsetId, kind, event.createdAt, event.userId?.toString(), event.comment?.modes)
    }

    /**
     * Who nominated a ranked beatmap set at last: who nominated it after its last reset or disqualification. Null when that doesn't
     * add up: as many as osu! says it has now, or 2 or 3 on the old ones of which osu! doesn't say it.
     */
    private fun findFromNominations(found: BeatmapSet): List<BeatmapSet.CurrentNomination>? {
        val events = progress.findNominations(found.id).sortedWith(compareBy({ it.at }, { it.id }))
        val afterReset = events.drop(events.indexOfLast { it.kind == NominationEvent.Kind.Reset } + 1)
        val nominations =
            afterReset
                .filter { it.kind == NominationEvent.Kind.Nominate && it.userId != null }
                .associate { it.userId!! to it.modes }
                .map { (userId, modes) -> BeatmapSet.CurrentNomination(modes, false, userId.toLong()) }

        val expected = found.nominationsSummary?.current ?: 0
        val addsUp = if (expected > 0) nominations.size == expected else nominations.size in 2..3
        return nominations.takeIf { addsUp }
    }

    /** A ranked beatmap set with who nominated it, before the nominators are looked up */
    private data class Prepared(
        val beatmapSet: BeatmapSet,
        val nominations: List<BeatmapSet.CurrentNomination>,
        val askedAbout: Boolean = false,
        val checked: Boolean = false,
        val disagreed: Boolean = false,
    )

    /** @return null when it isn't imported */
    private fun prepare(found: BeatmapSet): Prepared? {
        // Loved and anything else not ranked is left out, and what the planner has already stays as it is
        if (found.ranked !in setOf(1, 2) || beatmaps.findById(found.id) != null) {
            return null
        }

        val fromNominations = findFromNominations(found)
        if (fromNominations != null && ++told % checkEvery != 0) {
            return Prepared(found, fromNominations)
        }

        pace()
        val beatmapSet = osuClient.findBeatmapWithId(token(), found.id)
        if (fromNominations == null) {
            // From before osu! kept who nominated, which says nothing about the nominators
            val nominations = beatmapSet?.currentNominations.orEmpty()
            return beatmapSet?.takeIf { nominations.isNotEmpty() }?.let { Prepared(it, nominations, askedAbout = true) }
        }
        if (beatmapSet == null) {
            return Prepared(found, fromNominations)
        }

        val disagreed = idsOf(fromNominations) != idsOf(beatmapSet.currentNominations)
        if (disagreed) {
            val theyTell = idsOf(fromNominations)
            val osu = idsOf(beatmapSet.currentNominations)
            log.warn {
                "Beatmap set ${found.id}: its nominations tell of $theyTell, osu! of $osu"
            }
        }
        // What osu! tells of the beatmap set itself is what counts
        return Prepared(beatmapSet, beatmapSet.currentNominations, checked = true, disagreed = disagreed)
    }

    private fun idsOf(nominations: List<BeatmapSet.CurrentNomination>) = nominations.map { it.userId }.toSet()

    /** @return true when it was added */
    private fun insert(prepared: Prepared): Boolean {
        val beatmapSet = prepared.beatmapSet

        // Nobody waits for it, so the name osu! gives is enough for the mapper
        users.saveAllMissing(listOf(User(beatmapSet.user_id, beatmapSet.creator, emptyList())))

        val modes = beatmapSet.beatmaps.map { it.mode }.distinct()
        val main = beatmapSet.findMainGamemodes()?.firstOrNull() ?: modes.firstOrNull() ?: Gamemode.osu
        val certain = mutableMapOf<String, List<Gamemode>>()
        val unknown = mutableListOf<String>()

        prepared.nominations.forEach { nomination ->
            val userId = nomination.userId.toString()
            val told = nomination.rulesets.orEmpty().mapNotNull { ruleset -> Gamemode.entries.find { it.name == ruleset } }
            when {
                told.isNotEmpty() -> certain[userId] = told
                modes.size <= 1 -> certain[userId] = listOf(main)
                else -> unknown += userId
            }
        }

        val rankedAt = beatmapSet.ranked_date ?: beatmapSet.last_updated
        val guess = HistoryGuess(beatmapSet.id, modes, main, certain, unknown, rankedAt.atZone(ZoneOffset.UTC).year)
        // Until the gamemodes are worked out, whoever is unknown nominated the main gamemode
        val gamemodes =
            if (unknown.isEmpty()) {
                certain
                    .toList()
                    .flatMap { (userId, told) ->
                        told.map { it to userId }
                    }.groupBy({ it.first }, { it.second })
                    .map { (mode, ids) ->
                        BeatmapGamemode(mode, ids.distinct().map { BeatmapNominator(it, true) })
                    }
            } else {
                ModeGuesser.assign(guess, ModeGuesser.Votes())
            }

        val beatmap =
            Beatmap(
                osuId = beatmapSet.id,
                artist = beatmapSet.artist,
                title = beatmapSet.title,
                note = "",
                mapper = beatmapSet.creator,
                mapperId = beatmapSet.user_id,
                status = BeatmapStatus.Ranked,
                gamemodes = gamemodes.sortedBy { it.gamemode },
                dateAdded = rankedAt,
                dateUpdated = beatmapSet.last_updated,
                dateRanked = rankedAt,
                mainGamemodes = beatmapSet.findMainGamemodes(),
            )

        if (beatmaps.insertIfMissing(beatmap) != null) {
            return false
        }
        if (unknown.isNotEmpty()) {
            progress.saveGuess(guess)
        }
        events.insert(
            BeatmapEvent(
                beatmapSet.id,
                BeatmapEventType.Added,
                Instant.now(),
                null,
                beatmap.gamemodes.map { it.gamemode },
                status = BeatmapStatus.Ranked,
            ),
        )

        return true
    }

    /**
     * Nominators the planner doesn't know well are asked of osu!, 50 at once. Restricted ones get the name the staff log knows them
     * by.
     */
    private fun lookUpNominators(
        userIds: List<String>,
        names: Map<String, String>,
    ) {
        // Someone in a group or marked as former is known well enough. Anybody else may only have been seen as a mapper so far, with
        // the name of the search and no roles, or be restricted. Each of those is asked about once, not for every beatmap.
        val unknown =
            userIds.filter { userId ->
                val known = users.findUser(userId)
                val wellKnown =
                    known != null && known.restricted != true && known.gamemodes.any { it.role.canNominate || it.role == Role.Former }
                !wellKnown && lookedUp.add(userId)
            }

        unknown.chunked(OsuHttpClient.USERS_PER_CALL).forEach { batch ->
            pace()
            val found = userService.forceFindUsersByIds(token(), batch)
            if (found.isEmpty()) {
                // osu! gave no clear answer, they are asked about again with a later beatmap
                lookedUp.removeAll(batch.toSet())
            }
            found.filter { it.restricted == true }.forEach { user ->
                val name = names[user.osuId]
                if (name != null && user.username != name) {
                    users.saveUser(user.copy(username = name))
                }
            }
        }
    }

    /** Works out who nominated which gamemode of every ranked beatmap osu! didn't tell it of */
    private fun guessModes() {
        status = status.copy(phase = Phase.Modes)
        val guesses = progress.findGuesses()
        val votes =
            ModeGuesser.findVotes(
                beatmaps.findAll { it.status == BeatmapStatus.Ranked },
                guesses,
                roleHistory.findAll(),
            )
        var changed = 0

        guesses.forEach { guess ->
            val gamemodes = ModeGuesser.assign(guess, votes)
            val result =
                beatmaps.modify(guess.beatmapId) { current ->
                    // Corrected by hand once a gamemode is no longer marked as guessed, or its nominators changed, which stays as it is
                    val untouched = current.gamemodes.all { it.guessed } && nominatorsOf(current) == nominatorsOf(gamemodes)
                    current.copy(gamemodes = gamemodes).takeIf { untouched && current.gamemodes != gamemodes }
                }
            if (result != null) {
                changed++
            }
        }

        log.info { "Worked out the gamemodes of the nominators of ${guesses.size} ranked beatmaps, $changed changed" }
        userService.markFormerNominators(beatmaps.findAll { it.status == BeatmapStatus.Ranked })
        status = status.copy(guessed = guesses.size)
    }

    private fun nominatorsOf(beatmap: Beatmap): Set<String> = nominatorsOf(beatmap.gamemodes)

    private fun nominatorsOf(gamemodes: List<BeatmapGamemode>): Set<String> =
        gamemodes
            .flatMap { it.nominators }
            .map { it.nominatorId }
            .filter { it != UserService.MISSING_USER_ID }
            .toSet()

    private fun token(): String = checkNotNull(osuService.findClientToken()) { "osu! gave no token to the planner" }

    /** Keeps the calls to osu! apart, slower than the rest of the planner as it asks osu! many times */
    private fun pace() {
        val wait = lastCall + interval - clock()
        if (wait > 0) {
            sleeper.sleep(wait)
        }
        lastCall = clock()
    }

    companion object {
        /** A month of nominations is about 30 pages, more means osu! doesn't keep to the days asked for */
        private const val MAX_PAGES_PER_MONTH = 500

        private const val ATTEMPTS = 3

        private val NOMINATION_TYPES =
            listOf(BeatmapsetEventType.NOMINATE, BeatmapsetEventType.NOMINATION_RESET, BeatmapsetEventType.DISQUALIFY)
    }

    @PreDestroy
    fun shutdown() {
        stopping = true
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
