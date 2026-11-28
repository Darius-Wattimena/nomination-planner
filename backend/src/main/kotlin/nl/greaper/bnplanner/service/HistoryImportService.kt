package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.datasource.HistoryImportDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.util.Sleeper
import nl.greaper.bnplanner.util.findMainGamemodes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Imports every ranked beatmap of osu! with its nominators, meant to run once on a developer's own machine, whose export is imported
 * on the servers afterwards. It takes long, as osu! is asked about each beatmap set on its own, so it continues where it stopped
 * after a restart. Afterwards it works out who nominated which gamemode of the old beatmaps with several gamemodes.
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
) {
    private val log = KotlinLogging.logger { }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "history-import").apply { isDaemon = true } }
    private val interval = 60_000L / perMinute.coerceAtLeast(1)
    private var lastCall = 0L

    enum class Phase {
        Roles,
        Beatmaps,
        Modes,
    }

    data class Status(
        val running: Boolean,
        val phase: Phase? = null,
        /** Ranked beatmap sets of osu! seen so far, of all runs */
        val seen: Int = 0,
        val imported: Int = 0,
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
                done = it.done,
            )
        }

    @Volatile
    private var stopping = false

    /** Pages of one search of osu!, which stops after 10,000 results. Only tests change it. */
    internal var pagesPerSearch = 150

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

    /** Stops after the beatmap set it is busy with, it continues there when started again */
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

        status = status.copy(phase = Phase.Beatmaps)
        var current = progress.findProgress()

        while (!current.done && !stopping) {
            pace()
            val page = osuClient.searchRankedBeatmapsets(token(), current.since, current.cursor)
            var imported = current.imported

            for (found in page.beatmapsets) {
                if (stopping) {
                    // The page is read again, what was imported of it is skipped
                    return
                }
                if (importRanked(found, names)) {
                    imported++
                }
                status = status.copy(reached = found.ranked_date, imported = imported)
            }

            val lastDay =
                page.beatmapsets
                    .lastOrNull()
                    ?.ranked_date
                    ?.atZone(ZoneOffset.UTC)
                    ?.toLocalDate()
            // A new search from the last day reached, before osu! stops at 10,000 results. Who was seen of that day is skipped.
            val restart = current.pages + 1 >= pagesPerSearch && page.cursorString != null && lastDay != null && lastDay != current.since
            current =
                HistoryImportDataSource.Progress(
                    since = if (restart) lastDay else current.since,
                    cursor = if (restart) null else page.cursorString,
                    pages = if (restart) 0 else current.pages + 1,
                    done = page.cursorString == null || page.beatmapsets.isEmpty(),
                    seen = current.seen + page.beatmapsets.size,
                    imported = imported,
                )
            progress.saveProgress(current)
            status = status.copy(seen = current.seen, imported = current.imported, done = current.done)
        }

        if (current.done && !stopping) {
            guessModes()
        }
    }

    /** @return true when it was added */
    internal fun importRanked(
        found: BeatmapSet,
        names: Map<String, String>,
    ): Boolean {
        // Loved and anything else not ranked is left out, and what the planner has already stays as it is
        if (found.ranked !in setOf(1, 2) || beatmaps.findById(found.id) != null) {
            return false
        }

        pace()
        val beatmapSet = osuClient.findBeatmapWithId(token(), found.id) ?: return false
        val nominations = beatmapSet.currentNominations

        // From before osu! kept who nominated, which says nothing about the nominators
        if (nominations.isEmpty()) {
            return false
        }

        // Nobody waits for it, so the name osu! gives is enough for the mapper
        users.saveAllMissing(listOf(User(beatmapSet.user_id, beatmapSet.creator, emptyList())))
        nominations.forEach { findNominator(it.userId.toString(), names) }

        val modes = beatmapSet.beatmaps.map { it.mode }.distinct()
        val main = beatmapSet.findMainGamemodes()?.firstOrNull() ?: modes.firstOrNull() ?: Gamemode.osu
        val certain = mutableMapOf<String, List<Gamemode>>()
        val unknown = mutableListOf<String>()

        nominations.forEach { nomination ->
            val userId = nomination.userId.toString()
            val told = nomination.rulesets.orEmpty().mapNotNull { ruleset -> Gamemode.entries.find { it.name == ruleset } }
            when {
                told.isNotEmpty() -> certain[userId] = told
                modes.size <= 1 -> certain[userId] = listOf(main)
                else -> unknown += userId
            }
        }

        val rankedAt = beatmapSet.ranked_date ?: found.ranked_date ?: beatmapSet.last_updated
        val guess = HistoryGuess(found.id, modes, main, certain, unknown, rankedAt.atZone(ZoneOffset.UTC).year)
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
                osuId = found.id,
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
                found.id,
                BeatmapEventType.Added,
                Instant.now(),
                null,
                beatmap.gamemodes.map { it.gamemode },
                status = BeatmapStatus.Ranked,
            ),
        )

        return true
    }

    /** A nominator the planner doesn't know is asked of osu!. Restricted ones get the name the staff log knows them by. */
    private fun findNominator(
        userId: String,
        names: Map<String, String>,
    ) {
        val known = users.findUser(userId)
        if (known != null && known.restricted != true) {
            return
        }

        pace()
        val found = userService.forceFindUserById(token(), userId, logEventToDiscord = false)
        val name = names[userId]
        if (found?.restricted == true && name != null && found.username != name) {
            users.saveUser(found.copy(username = name))
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

    @PreDestroy
    fun shutdown() {
        stopping = true
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
