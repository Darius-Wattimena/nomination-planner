package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.PlannerUpdate
import nl.greaper.bnplanner.model.RankedUpdate
import nl.greaper.bnplanner.service.LiveUpdates
import nl.greaper.bnplanner.service.PlannerService
import nl.greaper.bnplanner.service.RankedService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult

@RestController
@RequestMapping("/v2/planner")
class PlannerController(
    private val service: PlannerService,
    private val rankedService: RankedService,
    private val liveUpdates: LiveUpdates,
) {
    @GetMapping
    @RolesAllowed(RolePermission.VIEWER)
    fun find(
        @RequestParam(required = false) since: String?,
    ): PlannerUpdate = service.find(since)

    /** Like the planner, only answers once something changed since then, or after a while */
    @GetMapping("/wait")
    @RolesAllowed(RolePermission.VIEWER)
    fun waitForChanges(
        @RequestParam(required = false) since: String?,
    ): DeferredResult<PlannerUpdate> = liveUpdates.wait(since)

    @GetMapping("/ranked")
    @RolesAllowed(RolePermission.VIEWER)
    fun findRanked(
        @RequestParam(required = false) since: String?,
    ): RankedUpdate = rankedService.find(since)
}
