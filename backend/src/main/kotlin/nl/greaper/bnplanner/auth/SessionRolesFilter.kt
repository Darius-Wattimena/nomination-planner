package nl.greaper.bnplanner.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.greaper.bnplanner.service.UserService
import nl.greaper.bnplanner.util.getHighestRoleForUser
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Gives someone who logged in the roles they have right now, so a change of their roles counts straight away.
 *
 * Someone the planner no longer knows is treated as not logged in. Nothing of this is stored in the session.
 */
class SessionRolesFilter(
    private val userService: UserService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val login = SecurityContextHolder.getContext().authentication

        if (login is OAuth2AuthenticationToken) {
            val user = userService.findUserById(login.name)
            val roles = getHighestRoleForUser(user).roles.map { SimpleGrantedAuthority("ROLE_$it") }

            // A new context, changing the one of the session would store the roles in it
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = user?.let { OAuth2AuthenticationToken(login.principal, roles, login.authorizedClientRegistrationId) }
            SecurityContextHolder.setContext(context)
        }

        chain.doFilter(request, response)
    }
}
