package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.RankedBeatmap
import nl.greaper.bnplanner.model.RankedMode
import nl.greaper.bnplanner.model.RankedUpdate
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.security.MessageDigest

/**
 * The statistics are added up by the website, so switching between modes and periods doesn't need the backend. Every ranked beatmap
 * of osu!'s history is tens of thousands, so all of them are put together once and kept as JSON, until a ranked beatmap changes.
 */
@Service
class RankedService(
    private val beatmapDataSource: BeatmapDataSource,
    private val userDataSource: UserDataSource,
    private val tokens: FeedTokens,
    private val objectMapper: ObjectMapper = jacksonMapperBuilder().build(),
    /** While ranked beatmaps keep changing, like during an import, everything is put together again at most this often */
    private val rebuildEveryMillis: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Autowired
    constructor(beatmapDataSource: BeatmapDataSource, userDataSource: UserDataSource, tokens: FeedTokens, objectMapper: ObjectMapper) :
        this(beatmapDataSource, userDataSource, tokens, objectMapper, 30_000)

    /** Every ranked beatmap at one moment */
    private class Snapshot(
        val beatmapVersion: Long,
        val update: RankedUpdate,
        val json: ByteArray,
        val ids: Set<String>,
        /** Describes the ranked beatmaps, the same after a restart as long as none of them changed */
        val content: String,
        val builtAt: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun find(since: String?): RankedUpdate = findUpdate(since).first

    /** The same as JSON, which is kept for an update of everything */
    fun findJson(since: String?): ByteArray {
        val (update, json) = findUpdate(since)
        return json ?: objectMapper.writeValueAsBytes(update)
    }

    private fun findUpdate(since: String?): Pair<RankedUpdate, ByteArray?> {
        val versions = tokens.parse(since)

        if (versions == null) {
            val current = findSnapshot()
            // After a restart nothing ranked changed, so only the names are sent again, they may have
            if (current.content == tokens.contentOf(since) && isCurrent(current)) {
                return current.update.copy(token = tokens.create(current.content), isComplete = false, beatmaps = emptyList()) to null
            }
            return current.update to current.json
        }

        val token = tokens.create(findSnapshot().takeIf { isCurrent(it) }?.content)
        // Read once, a beatmap can change or be deleted in between
        val changed =
            beatmapDataSource.changes
                .findSince(versions.beatmaps)
                .associateWith { id -> beatmapDataSource.findById(id)?.takeIf { it.isRanked() } }
        val beatmaps = changed.values.filterNotNull()
        val changedUsers = userDataSource.changes.findSince(versions.users)
        val mentioned = beatmaps.flatMap { findUserIds(it) }.toSet() + changedUsers

        val update =
            RankedUpdate(
                token = token,
                isComplete = false,
                names = findNames(mentioned),
                beatmaps = beatmaps.map { toRankedBeatmap(it) },
                removed = changed.filterValues { it == null }.keys.toList(),
            )
        return update to null
    }

    /** Whether no ranked beatmap changed since it was put together */
    private fun isCurrent(snapshot: Snapshot): Boolean =
        beatmapDataSource.changes
            .findSince(snapshot.beatmapVersion)
            .none { id -> id in snapshot.ids || beatmapDataSource.findById(id)?.isRanked() == true }

    @Synchronized
    private fun findSnapshot(): Snapshot {
        val current = snapshot
        if (current != null && (clock() - current.builtAt < rebuildEveryMillis || isCurrent(current))) {
            // Someone receiving one that isn't current anymore gets what changed since with their next update
            return current
        }

        val beatmapVersion = beatmapDataSource.changes.version
        val token = tokens.create()
        val beatmaps = beatmapDataSource.findAll { it.isRanked() }.map { toRankedBeatmap(it) }
        val content = describe(beatmaps)
        val update =
            RankedUpdate(
                token = "$token/$content",
                isComplete = true,
                names = findNames(beatmaps.flatMap { beatmap -> beatmap.modes.flatMap { it.by } + beatmap.mapper }.toSet()),
                beatmaps = beatmaps,
                removed = emptyList(),
            )

        return Snapshot(beatmapVersion, update, objectMapper.writeValueAsBytes(update), beatmaps.map { it.id }.toSet(), content, clock())
            .also { snapshot = it }
    }

    private fun describe(beatmaps: List<RankedBeatmap>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Someone who has the same beatmaps in an earlier format needs them again
        digest.update("format $FORMAT|".toByteArray())
        beatmaps.sortedBy { it.id }.forEach { beatmap ->
            val modes = beatmap.modes.joinToString(";") { "${it.mode.name}:${it.by.joinToString(",")}" }
            digest.update("${beatmap.id}|${beatmap.title}|${beatmap.mapper}|${beatmap.ranked}|${beatmap.added}|$modes\n".toByteArray())
        }
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Raised when what is sent of each beatmap changes */
        private const val FORMAT = 2
    }

    private fun findNames(ids: Set<String>): Map<String, String> =
        ids.mapNotNull { userDataSource.findUser(it) }.associate { it.osuId to it.username }

    private fun Beatmap.isRanked(): Boolean = status == BeatmapStatus.Ranked

    private fun findUserIds(beatmap: Beatmap): List<String> =
        beatmap.gamemodes
            .flatMap { it.nominators }
            .map { it.nominatorId } + beatmap.mapperId

    private fun toRankedBeatmap(beatmap: Beatmap): RankedBeatmap =
        RankedBeatmap(
            id = beatmap.osuId,
            title = beatmap.title,
            mapper = beatmap.mapperId,
            ranked = beatmap.dateRanked?.epochSecond,
            modes =
                beatmap.gamemodes.map { mode ->
                    RankedMode(mode.gamemode, mode.nominators.map { it.nominatorId }.filter { it != MISSING_USER_ID })
                },
            added = beatmap.dateAdded.epochSecond,
        )
}
