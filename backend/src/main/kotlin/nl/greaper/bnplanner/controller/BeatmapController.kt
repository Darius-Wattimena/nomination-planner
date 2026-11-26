package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.OsuLogin
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapPage
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.ExposedBeatmap
import nl.greaper.bnplanner.model.beatmap.NewBeatmap
import nl.greaper.bnplanner.service.BeatmapService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v2/beatmap")
class BeatmapController(
    val service: BeatmapService,
) {
    private fun notOnPlanner(id: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "Beatmap $id is not on the planner")

    @GetMapping("/{id}")
    @RolesAllowed(RolePermission.VIEWER)
    fun findBeatmap(
        @PathVariable("id") id: String,
    ): ExposedBeatmap = service.findExposedBeatmap(id) ?: throw notOnPlanner(id)

    @GetMapping("/count")
    @RolesAllowed(RolePermission.VIEWER)
    fun countBeatmaps(
        @RequestParam page: BeatmapPage,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) artist: String?,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) mapper: String?,
        @RequestParam(required = false) status: Set<BeatmapStatus>?,
        @RequestParam(required = false) nominators: Set<String>?,
        @RequestParam(required = false) gamemodes: Set<Gamemode>?,
        @RequestParam(required = false) missingNominator: Set<Gamemode>?,
    ): Int =
        service.countBeatmaps(
            search = search,
            artist = artist,
            title = title,
            mapper = mapper,
            status = status ?: emptySet(),
            nominators = nominators ?: emptySet(),
            page = page,
            gamemodes = gamemodes ?: emptySet(),
            missingNominator = missingNominator ?: emptySet(),
        )

    @GetMapping("/find/table")
    @RolesAllowed(RolePermission.VIEWER)
    fun findBeatmapsTable(
        @RequestParam page: BeatmapPage,
        @RequestParam pageNumber: Int,
        @RequestParam pageLimit: PageLimit,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) artist: String?,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) mapper: String?,
        @RequestParam(required = false) status: Set<BeatmapStatus>?,
        @RequestParam(required = false) nominators: Set<String>?,
        @RequestParam(required = false) gamemodes: Set<Gamemode>?,
        @RequestParam(required = false) missingNominator: Set<Gamemode>?,
    ): List<ExposedBeatmap> =
        service.findBeatmaps(
            search = search,
            artist = artist,
            title = title,
            mapper = mapper,
            status = status ?: emptySet(),
            nominators = nominators ?: emptySet(),
            page = page,
            pageNumber = pageNumber,
            pageLimit = pageLimit,
            gamemodes = gamemodes ?: emptySet(),
            missingNominator = missingNominator ?: emptySet(),
        )

    @PostMapping("/add")
    @RolesAllowed(RolePermission.EDITOR)
    fun addBeatmap(
        login: OsuLogin,
        @RequestBody newBeatmap: NewBeatmap,
    ): ExposedBeatmap =
        service.addBeatmap(login, newBeatmap)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "osu! doesn't know beatmap ${newBeatmap.osuId}")

    @PatchMapping("/{id}/{mode}/nominator")
    @RolesAllowed(RolePermission.EDITOR)
    fun updateSingleNominator(
        login: OsuLogin,
        @RequestParam old: String,
        @RequestParam new: String,
        @PathVariable("id") id: String,
        @PathVariable("mode") mode: Gamemode,
    ): ExposedBeatmap =
        service.updateBeatmapNominator(login, id, mode, old, new)
            ?: throw if (service.findBeatmap(id) == null) {
                notOnPlanner(id)
            } else {
                ResponseStatusException(HttpStatus.CONFLICT, "Someone else changed the nominators first")
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
        if (!service.updateBeatmapNote(login, id, body ?: "")) throw notOnPlanner(id)
    }

    @DeleteMapping("/{id}/delete")
    @RolesAllowed(RolePermission.EDITOR)
    fun deleteBeatmap(
        login: OsuLogin,
        @PathVariable("id") id: String,
    ) {
        service.deleteBeatmap(login, id)
    }
}
