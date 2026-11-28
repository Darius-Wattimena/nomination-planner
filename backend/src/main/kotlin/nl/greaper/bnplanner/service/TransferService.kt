package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import org.springframework.stereotype.Service
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Moves ranked beatmaps from one planner to another, like the ranked history imported on a developer's own machine to the servers.
 * Only for ranked beatmaps, so nothing in progress can ever be changed by it. An export of the whole ranked history is tens of
 * megabytes, so an import reads it from a file a batch at a time instead of holding all of it.
 */
@Service
class TransferService(
    private val beatmaps: BeatmapDataSource,
    private val events: BeatmapEventDataSource,
    private val users: UserDataSource,
    private val userService: UserService,
    private val roleHistory: RoleHistoryService,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger { }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "import").apply { isDaemon = true } }

    /** What is in an export, the version changes when the shape does */
    data class PlannerExport(
        val version: Int,
        val exportedAt: Instant,
        val beatmaps: List<Beatmap>,
        val events: List<BeatmapEvent>,
        val users: List<User>,
        val roleHistory: List<RoleChange>,
    )

    /** What an import does, or would do */
    data class ImportSummary(
        val newBeatmaps: Int,
        /** Already on this planner, these stay as they are */
        val knownBeatmaps: Int,
        /** Not ranked, which an import never adds */
        val notRanked: Int,
        val newUsers: Int,
        /** Already known, these stay as they are */
        val knownUsers: Int,
        /** The history of the new beatmaps */
        val events: Int,
        val roleChanges: Int,
        val exportedAt: Instant,
    )

    data class ImportStatus(
        val running: Boolean,
        val last: ImportSummary? = null,
        /** Why the last import stopped, null when it went well */
        val failed: String? = null,
    )

    class ImportException(
        message: String,
    ) : RuntimeException(message)

    @Volatile
    private var status = ImportStatus(running = false)

    /** Every ranked beatmap, its history, the users it mentions and the role history, gzipped */
    fun export(): ByteArray {
        val ranked = beatmaps.findAll { it.status == BeatmapStatus.Ranked }
        val mentioned = ranked.flatMap { beatmap -> beatmap.gamemodes.flatMap { it.nominators }.map { it.nominatorId } + beatmap.mapperId }
        val export =
            PlannerExport(
                version = VERSION,
                exportedAt = Instant.now(),
                beatmaps = ranked,
                events = events.findAllOf(ranked.map { it.osuId }),
                users = mentioned.toSet().mapNotNull { users.findUser(it) },
                roleHistory = roleHistory.findAll(),
            )
        log.info {
            "Exported ${export.beatmaps.size} ranked beatmaps, ${export.users.size} users and ${export.roleHistory.size} role changes"
        }

        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { objectMapper.writeValue(it, export) }
        return bytes.toByteArray()
    }

    /** @throws ImportException when it isn't an export of the planner */
    fun check(file: ByteArray): ImportSummary = withTemporary(file) { check(it) }

    /** @throws ImportException when it isn't an export of the planner */
    fun check(file: Path): ImportSummary = summarize(file)

    /** The same as [start] with a file, only for tests */
    internal fun start(file: ByteArray): ImportSummary = start(Files.createTempFile("import", ".json.gz").also { Files.write(it, file) })

    /**
     * Starts adding what is new in the background, the website asks how it goes with [findStatus]. The file is deleted afterwards.
     *
     * @throws ImportException when it isn't an export of the planner, or another import is still going
     */
    @Synchronized
    fun start(file: Path): ImportSummary {
        try {
            if (status.running) {
                throw ImportException("Another import is still going")
            }

            val summary = summarize(file)
            status = ImportStatus(running = true, last = summary)
            executor.execute {
                try {
                    run(file, summary)
                } finally {
                    Files.deleteIfExists(file)
                }
            }
            return summary
        } catch (ex: Exception) {
            Files.deleteIfExists(file)
            throw ex
        }
    }

    fun findStatus(): ImportStatus = status

    /** Waits until the import is done, only for tests */
    internal fun finish() {
        executor.submit {}.get()
    }

    private fun run(
        file: Path,
        summary: ImportSummary,
    ) {
        status =
            try {
                // The names first, so the beatmaps show them straight away
                var addedUsers = 0
                var roleChanges = 0
                read(
                    file,
                    Visitor(
                        // Nobody on the planner waits for them, they only nominated or mapped beatmaps that are ranked
                        users = { addedUsers += users.saveAllMissing(it, forPlanner = false).size },
                        roleHistory = { roleChanges += roleHistory.saveAll(it) },
                    ),
                )
                val addedIds = mutableSetOf<String>()
                read(
                    file,
                    Visitor(
                        beatmaps = { batch ->
                            addedIds += beatmaps.insertAllMissing(batch.filter { it.status == BeatmapStatus.Ranked }).map { it.osuId }
                        },
                        events = { batch -> events.insertAll(batch.filter { it.beatmapId in addedIds }) },
                    ),
                )
                // Also the users this planner knew already may have nominated some of the new beatmaps
                userService.markFormerNominators(beatmaps.findAll { it.status == BeatmapStatus.Ranked })
                log.info {
                    "Imported ${addedIds.size} ranked beatmaps, $addedUsers users and $roleChanges role changes " +
                        "of the export of ${summary.exportedAt}"
                }
                ImportStatus(
                    running = false,
                    last = summary.copy(newBeatmaps = addedIds.size, newUsers = addedUsers, roleChanges = roleChanges),
                )
            } catch (ex: Exception) {
                log.error(ex) { "The import stopped" }
                ImportStatus(running = false, last = summary, failed = "The import stopped: ${ex.message}")
            }
    }

    /** What to do with each batch of an export */
    private class Visitor(
        val beatmaps: (List<Beatmap>) -> Unit = {},
        val events: (List<BeatmapEvent>) -> Unit = {},
        val users: (List<User>) -> Unit = {},
        val roleHistory: (List<RoleChange>) -> Unit = {},
    )

    /**
     * Goes through an export a batch at a time
     *
     * @return when it was exported
     * @throws ImportException when it isn't an export of this version of the planner
     */
    private fun read(
        file: Path,
        visitor: Visitor,
    ): Instant {
        var exportedAt: Instant? = null
        try {
            open(file).use { input ->
                objectMapper.createParser(input).use { parser ->
                    if (parser.nextToken() != JsonToken.START_OBJECT) {
                        throw ImportException(NOT_AN_EXPORT)
                    }
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        val name = parser.currentName()
                        parser.nextToken()
                        when (name) {
                            "exportedAt" -> {
                                exportedAt = part(parser, Instant::class.java)
                            }

                            "beatmaps" -> {
                                each(parser, Beatmap::class.java, visitor.beatmaps)
                            }

                            "events" -> {
                                each(parser, BeatmapEvent::class.java, visitor.events)
                            }

                            "users" -> {
                                each(parser, User::class.java, visitor.users)
                            }

                            "roleHistory" -> {
                                each(parser, RoleChange::class.java, visitor.roleHistory)
                            }

                            else -> {
                                parser.skipChildren()
                            }
                        }
                    }
                }
            }
        } catch (ex: ImportException) {
            throw ex
        } catch (ex: Exception) {
            log.info(ex) { NOT_AN_EXPORT }
            throw ImportException(NOT_AN_EXPORT)
        }

        return exportedAt ?: throw ImportException(NOT_AN_EXPORT)
    }

    /**
     * Only looks for the version, skipping everything else, before anything is done with the rest, whatever order the fields are in
     *
     * @throws ImportException when it isn't an export of this version of the planner
     */
    private fun checkVersion(file: Path) {
        val version =
            try {
                open(file).use { input ->
                    objectMapper.createParser(input).use { parser ->
                        if (parser.nextToken() != JsonToken.START_OBJECT) {
                            throw ImportException(NOT_AN_EXPORT)
                        }
                        var found: Int? = null
                        while (found == null && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            val name = parser.currentName()
                            parser.nextToken()
                            if (name == "version") found = part(parser, Int::class.java) else parser.skipChildren()
                        }
                        found
                    }
                }
            } catch (ex: ImportException) {
                throw ex
            } catch (ex: Exception) {
                log.info(ex) { NOT_AN_EXPORT }
                throw ImportException(NOT_AN_EXPORT)
            }

        when (version) {
            VERSION -> return
            null -> throw ImportException(NOT_AN_EXPORT)
            else -> throw ImportException("This export has version $version, this planner reads version $VERSION")
        }
    }

    private fun <T> each(
        parser: JsonParser,
        type: Class<T>,
        handle: (List<T>) -> Unit,
    ) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw ImportException(NOT_AN_EXPORT)
        }
        val batch = ArrayList<T>(BATCH_SIZE)
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            batch += part(parser, type)
            if (batch.size == BATCH_SIZE) {
                handle(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            handle(batch.toList())
        }
    }

    /** One value in the middle of an export, which goes on after it */
    private fun <T> part(
        parser: JsonParser,
        type: Class<T>,
    ): T = objectMapper.readerFor(type).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser)

    /** Gzipped like an export, or not when someone unpacked it */
    private fun open(file: Path): InputStream {
        val input = BufferedInputStream(Files.newInputStream(file))
        input.mark(2)
        val gzipped = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gzipped) GZIPInputStream(input) else input
    }

    private fun summarize(file: Path): ImportSummary {
        checkVersion(file)
        val seen = mutableSetOf<String>()
        var known = 0
        var notRanked = 0
        val newIds = mutableSetOf<String>()
        val seenUsers = mutableSetOf<String>()
        var knownUsers = 0
        var newUsers = 0
        var events = 0
        val roleChanges = mutableSetOf<String>()

        val exportedAt =
            read(
                file,
                Visitor(
                    beatmaps = { batch ->
                        batch.filter { seen.add(it.osuId) }.forEach {
                            when {
                                it.status != BeatmapStatus.Ranked -> notRanked++
                                beatmaps.findById(it.osuId) != null -> known++
                                else -> newIds += it.osuId
                            }
                        }
                    },
                    events = { batch -> events += batch.count { it.beatmapId in newIds } },
                    users = { batch ->
                        batch.filter { seenUsers.add(it.osuId) }.forEach {
                            if (users.findUser(it.osuId) !=
                                null
                            ) {
                                knownUsers++
                            } else {
                                newUsers++
                            }
                        }
                    },
                    roleHistory = { batch -> batch.forEach { roleChanges += it.key } },
                ),
            )

        return ImportSummary(
            newBeatmaps = newIds.size,
            knownBeatmaps = known,
            notRanked = notRanked,
            newUsers = newUsers,
            knownUsers = knownUsers,
            events = events,
            roleChanges = roleChanges.size,
            exportedAt = exportedAt,
        )
    }

    private fun <T> withTemporary(
        file: ByteArray,
        use: (Path) -> T,
    ): T {
        val path = Files.createTempFile("import", ".json.gz")
        try {
            Files.write(path, file)
            return use(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val VERSION = 1

        private const val BATCH_SIZE = 500
        private const val NOT_AN_EXPORT = "This is not an export of the planner"
    }
}
