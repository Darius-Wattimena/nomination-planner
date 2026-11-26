package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.service.UserService
import nl.greaper.bnplanner.util.getHighestRoleForUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Who is logged in on this browser, logging in itself goes through /oauth2/authorization/osu */
@RestController
@RequestMapping("/v2/session")
class SessionController(
    private val userService: UserService,
) {
    data class Session(
        val user: User,
        val permission: RolePermission,
        /** To send along in the X-CSRF-TOKEN header with every change */
        val csrfToken: String,
    )

    /** Empty when nobody is logged in */
    @GetMapping
    fun find(csrf: CsrfToken): ResponseEntity<Session> {
        val login = SecurityContextHolder.getContext().authentication as? OAuth2AuthenticationToken
        // Known, as someone the planner doesn't know is not logged in
        val user = login?.let { userService.findUserById(it.name)!! } ?: return ResponseEntity.noContent().build()

        return ResponseEntity.ok(Session(user, getHighestRoleForUser(user), csrf.token))
    }
}
