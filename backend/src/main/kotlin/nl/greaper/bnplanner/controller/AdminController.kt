package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.SyncService
import nl.greaper.bnplanner.service.WeeklySummaryService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v2/admin")
class AdminController(
    private val syncService: SyncService,
    private val weeklySummaryService: WeeklySummaryService,
) {
    /** What the weekly summary would post right now, with the mode each part goes to */
    @PostMapping("/weekly-summary/preview", produces = [MediaType.TEXT_PLAIN_VALUE])
    @RolesAllowed(RolePermission.ADMIN)
    fun previewWeeklySummary(): String = weeklySummaryService.preview()

    /** Queues the users to sync, only the unknown and restricted ones unless forced */
    @PostMapping("/sync/users/ids")
    @RolesAllowed(RolePermission.ADMIN)
    fun syncUsers(
        @RequestParam(required = false) force: Boolean?,
        @RequestBody users: Set<String>,
    ): SyncService.Queued = syncService.syncUsers(users, force ?: false)

    /** Queues the beatmaps in progress with one of these statuses to sync, all of them without a status */
    @PostMapping("/sync/beatmaps/pending")
    @RolesAllowed(RolePermission.ADMIN)
    fun syncPendingBeatmaps(
        @RequestParam(required = false) status: Set<BeatmapStatus>?,
    ): SyncService.Queued {
        val notPending = status.orEmpty() - BeatmapPage.PENDING.statuses

        if (notPending.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a status of a beatmap in progress: ${notPending.joinToString()}")
        }

        return syncService.syncPendingBeatmaps(status.orEmpty())
    }

    /** How many beatmaps and users are still waiting to be synced in the background */
    @GetMapping("/sync/waiting")
    @RolesAllowed(RolePermission.ADMIN)
    fun findWaiting(): Int = syncService.findWaiting()
}
