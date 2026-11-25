package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import org.springframework.stereotype.Component
import java.util.UUID

/** Tells what someone already received, so a feed only has to send what changed since */
@Component
class FeedTokens(
    private val beatmapDataSource: BeatmapDataSource,
    private val userDataSource: UserDataSource,
) {
    /** Versions start over when the backend does, so nobody can continue on those of an earlier run */
    private val run = UUID.randomUUID().toString()

    data class Versions(
        val beatmaps: Long,
        val users: Long,
    )

    /** Has to be created before anything is read, so what changes meanwhile is part of the next update as well */
    fun create(): String = "$run/${beatmapDataSource.changes.version}/${userDataSource.changes.version}"

    /** Null when everything has to be sent */
    fun parse(token: String?): Versions? {
        val parts = token?.split("/")

        if (parts == null || parts.size != 3 || parts[0] != run) {
            return null
        }

        val beatmaps = parts[1].toLongOrNull()
        val users = parts[2].toLongOrNull()

        return if (beatmaps != null && users != null) Versions(beatmaps, users) else null
    }
}
