package nl.greaper.bnplanner.task

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.concurrent.thread

/** Runs in the background, the beatmaps can be used and changed meanwhile */
@Component
class BeatmapMigration(
    private val dataSource: BeatmapDataSource,
) {
    private val log = KotlinLogging.logger { }

    @EventListener(ApplicationReadyEvent::class)
    fun start(): Thread =
        thread(name = "beatmap-migration", isDaemon = true) {
            try {
                dataSource.migrate()
            } catch (ex: Exception) {
                // Nothing is lost, the old collection is still used and the next start tries again
                log.error(ex) { "Unable to migrate the beatmaps" }
            }
        }
}
