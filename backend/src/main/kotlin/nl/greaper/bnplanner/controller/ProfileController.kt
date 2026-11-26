package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.profile.ProfileStatisticsPairInfo
import nl.greaper.bnplanner.service.ProfileService
import nl.greaper.bnplanner.service.UserService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v2/profile")
class ProfileController(
    private val service: ProfileService,
    private val userService: UserService,
) {
    @RolesAllowed(RolePermission.VIEWER)
    @GetMapping("/{id}/pairing")
    fun getPairing(
        @PathVariable("id") id: String,
    ): List<ProfileStatisticsPairInfo> = service.getUserPairing(id)

    /** The modes someone without a role in a mode chose to follow, an empty list forgets them */
    @RolesAllowed(RolePermission.VIEWER)
    @PutMapping("/me/interests")
    fun saveInterests(
        @RequestHeader(HttpHeaders.AUTHORIZATION) osuApiToken: String,
        @RequestBody interests: Set<Gamemode>,
    ): User =
        userService.saveInterests(osuApiToken, interests)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user")
}
