package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.OsuLogin
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.BeatmapFilter
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmap
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.service.BeatmapService
import nl.greaper.bnplanner.service.BeatmapService.AddResult
import nl.greaper.bnplanner.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v2/beatmap")
class BeatmapController(
    val service: BeatmapService,
    val userService: UserService,
) {
    companion object {
        const val MAX_NOTE_LENGTH = 2000
    }

    private fun notOnPlanner(id: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "Beatmap $id is not on the planner")

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)

    @GetMapping("/{id}")
    @RolesAllowed(RolePermission.VIEWER)
    fun findBeatmap(
        @PathVariable("id") id: String,
    ): ExposedBeatmap = service.findExposedBeatmap(id) ?: throw notOnPlanner(id)

    @GetMapping("/count")
    @RolesAllowed(RolePermission.VIEWER)
    fun countBeatmaps(
        @ModelAttribute filter: BeatmapFilter,
    ): Int = service.countBeatmaps(filter)

    @GetMapping("/find/table")
    @RolesAllowed(RolePermission.VIEWER)
    fun findBeatmapsTable(
        @ModelAttribute filter: BeatmapFilter,
        @RequestParam pageNumber: Int,
        @RequestParam pageLimit: PageLimit,
    ): List<ExposedBeatmap> = service.findBeatmaps(filter, pageNumber, pageLimit)

    @PostMapping("/add")
    @RolesAllowed(RolePermission.EDITOR)
    fun addBeatmap(
        login: OsuLogin,
        @RequestBody newBeatmap: NewBeatmap,
    ): ExposedBeatmap {
        val id = newBeatmap.osuId

        if (!id.matches(Regex("[0-9]+"))) {
            throw badRequest("The id of a beatmap only has digits")
        }

        if (newBeatmap.gamemodes.isEmpty()) {
            throw badRequest("A beatmap needs at least one game mode")
        }

        return when (val result = service.addBeatmap(login, newBeatmap)) {
            is AddResult.Added -> result.beatmap
            AddResult.AlreadyOnPlanner -> throw ResponseStatusException(HttpStatus.CONFLICT, "Beatmap $id is already on the planner")
            AddResult.UnknownToOsu -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "osu! doesn't know beatmap $id")
        }
    }

    /** Sync a beatmap with osu! right away */
    @PostMapping("/{id}/sync")
    @RolesAllowed(RolePermission.EDITOR)
    fun syncBeatmap(
        login: OsuLogin,
        @PathVariable("id") id: String,
    ): ExposedBeatmap = service.syncBeatmap(login.accessToken, id) ?: throw notOnPlanner(id)

    @PatchMapping("/{id}/{mode}/nominator")
    @RolesAllowed(RolePermission.EDITOR)
    fun updateSingleNominator(
        login: OsuLogin,
        @RequestParam old: String,
        @RequestParam new: String,
        @PathVariable("id") id: String,
        @PathVariable("mode") mode: Gamemode,
    ): ExposedBeatmap {
        // The planner also knows the missing nominator
        if (userService.findUserById(new) == null) {
            throw badRequest("User $new is unknown to the planner")
        }

        return service.updateBeatmapNominator(login, id, mode, old, new)
            ?: throw if (service.findBeatmap(id) == null) {
                notOnPlanner(id)
            } else {
                ResponseStatusException(HttpStatus.CONFLICT, "Someone else changed the nominators first")
            }
    }

    @PatchMapping("/{id}/status")
    @RolesAllowed(RolePermission.ADMIN)
    fun updateStatus(
        login: OsuLogin,
        @RequestParam new: BeatmapStatus,
        @PathVariable("id") id: String,
    ) {
        if (!service.updateBeatmapStatus(login, id, new)) throw notOnPlanner(id)
    }

    @PatchMapping("/{id}/note", consumes = ["text/plain"])
    @RolesAllowed(RolePermission.EDITOR)
    fun updateNote(
        login: OsuLogin,
        // There is no body when clearing the note
        @RequestBody(required = false) body: String?,
        @PathVariable("id") id: String,
    ) {
        val note = body.orEmpty()

        if (note.length > MAX_NOTE_LENGTH) {
            throw badRequest("A note has at most $MAX_NOTE_LENGTH characters")
        }

        if (!service.updateBeatmapNote(login, id, note)) throw notOnPlanner(id)
    }

    @DeleteMapping("/{id}")
    @RolesAllowed(RolePermission.ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteBeatmap(
        login: OsuLogin,
        @PathVariable("id") id: String,
    ) {
        if (!service.deleteBeatmap(login, id)) throw notOnPlanner(id)
    }
}
