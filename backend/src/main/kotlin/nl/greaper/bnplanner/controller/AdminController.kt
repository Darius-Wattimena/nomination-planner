package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.OsuLogin
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.SyncService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/admin")
class AdminController(
    private val syncService: SyncService,
) {
    @PostMapping("/sync/users/ids")
    @RolesAllowed(RolePermission.ADMIN)
    fun syncUsers(
        login: OsuLogin,
        @RequestParam(required = false) force: Boolean?,
        @RequestBody users: Set<String>,
    ): SyncService.SyncInfo = syncService.syncUsers(login.accessToken, users, force ?: false)

    @PostMapping("/sync/beatmaps/pending")
    @RolesAllowed(RolePermission.ADMIN)
    fun syncPendingBeatmaps(
        login: OsuLogin,
        @RequestParam(required = false) status: Set<BeatmapStatus>? = null,
    ): SyncService.SyncInfo = syncService.syncPendingBeatmaps(login.accessToken, status.orEmpty())

    /** How many beatmaps are still waiting to be synced in the background */
    @GetMapping("/sync/waiting")
    @RolesAllowed(RolePermission.ADMIN)
    fun findWaiting(): Int = syncService.findWaiting()

    @PostMapping("/sync/beatmaps/single")
    @RolesAllowed(RolePermission.EDITOR)
    fun syncBeatmapById(
        login: OsuLogin,
        @RequestParam id: Int,
    ): SyncService.SyncInfo =
        syncService.syncSingleBeatmap(
            osuToken = login.accessToken,
            beatmapId = id.toString(),
        )
}
