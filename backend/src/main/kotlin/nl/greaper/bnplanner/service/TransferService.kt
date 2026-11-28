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
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Moves ranked beatmaps from one planner to another, like the ranked history imported on a developer's own machine to the servers.
 * Only for ranked beatmaps, so nothing in progress can ever be changed by it.
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
    fun check(file: ByteArray): ImportSummary = summarize(read(file))

    /**
     * Starts adding what is new in the background, the website asks how it goes with [findStatus]
     *
     * @throws ImportException when it isn't an export of the planner, or another import is still going
     */
    @Synchronized
    fun start(file: ByteArray): ImportSummary {
        if (status.running) {
            throw ImportException("Another import is still going")
        }

        val export = read(file)
        val summary = summarize(export)
        status = ImportStatus(running = true, last = summary)
        executor.execute { run(export, summary) }

        return summary
    }

    fun findStatus(): ImportStatus = status

    /** Waits until the import is done, only for tests */
    internal fun finish() {
        executor.submit {}.get()
    }

    private fun run(
        export: PlannerExport,
        summary: ImportSummary,
    ) {
        status =
            try {
                // The names first, so the beatmaps show them straight away
                val addedUsers = users.saveAllMissing(export.users)
                val added = beatmaps.insertAllMissing(export.beatmaps.filter { it.status == BeatmapStatus.Ranked })
                val addedIds = added.map { it.osuId }.toSet()
                events.insertAll(export.events.filter { it.beatmapId in addedIds })
                val roleChanges = roleHistory.saveAll(export.roleHistory)
                // Also the users this planner knew already may have nominated some of the new beatmaps
                userService.markFormerNominators(beatmaps.findAll { it.status == BeatmapStatus.Ranked })
                log.info {
                    "Imported ${added.size} ranked beatmaps, ${addedUsers.size} users and $roleChanges role changes " +
                        "of the export of ${export.exportedAt}"
                }
                ImportStatus(
                    running = false,
                    last = summary.copy(newBeatmaps = added.size, newUsers = addedUsers.size, roleChanges = roleChanges),
                )
            } catch (ex: Exception) {
                log.error(ex) { "The import stopped" }
                ImportStatus(running = false, last = summary, failed = "The import stopped: ${ex.message}")
            }
    }

    private fun read(file: ByteArray): PlannerExport {
        val export =
            try {
                val gzipped = file.size >= 2 && file[0] == 0x1f.toByte() && file[1] == 0x8b.toByte()
                val input = if (gzipped) GZIPInputStream(ByteArrayInputStream(file)) else ByteArrayInputStream(file)
                input.use { objectMapper.readValue<PlannerExport>(it) }
            } catch (ex: Exception) {
                log.info(ex) { "Not an export of the planner" }
                throw ImportException("This is not an export of the planner")
            }

        if (export.version != VERSION) {
            throw ImportException("This export has version ${export.version}, this planner reads version $VERSION")
        }

        return export
    }

    private fun summarize(export: PlannerExport): ImportSummary {
        val (ranked, notRanked) = export.beatmaps.distinctBy { it.osuId }.partition { it.status == BeatmapStatus.Ranked }
        val (known, new) = ranked.partition { beatmaps.findById(it.osuId) != null }
        val newIds = new.map { it.osuId }.toSet()
        val (knownUsers, newUsers) = export.users.distinctBy { it.osuId }.partition { users.findUser(it.osuId) != null }

        return ImportSummary(
            newBeatmaps = new.size,
            knownBeatmaps = known.size,
            notRanked = notRanked.size,
            newUsers = newUsers.size,
            knownUsers = knownUsers.size,
            events = export.events.count { it.beatmapId in newIds },
            roleChanges = export.roleHistory.distinctBy { it.key }.size,
            exportedAt = export.exportedAt,
        )
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val VERSION = 1
    }
}
