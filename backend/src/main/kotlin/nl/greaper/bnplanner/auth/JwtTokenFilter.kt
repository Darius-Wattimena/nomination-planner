package nl.greaper.bnplanner.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.greaper.bnplanner.config.AiessProperties
import nl.greaper.bnplanner.service.UserService
import nl.greaper.bnplanner.util.getAiessRole
import nl.greaper.bnplanner.util.getHighestRoleForUser
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

@Component
class JwtTokenFilter(
    private val aiess: AiessProperties,
    private val userService: UserService,
    private val tokenVerifier: OsuTokenVerifier,
) : OncePerRequestFilter() {
    private val log = KotlinLogging.logger { }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        // Get authorization header and validate
        val header = request.getHeader(AUTHORIZATION)
        if (header.isNullOrBlank()) {
            chain.doFilter(request, response)
            return
        }

        when {
            header.startsWith("Aiess ") -> {
                val token = findToken(header)

                if (isAiessToken(token)) {
                    val permission = getAiessRole()

                    val authentication =
                        UsernamePasswordAuthenticationToken(
                            permission,
                            token,
                            permission.roles.map { SimpleGrantedAuthority("ROLE_$it") },
                        )

                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                    chain.doFilter(request, response)
                    return
                }
            }

            header.startsWith("Bearer ") -> {
                // Get jwt token and validate
                val token = findToken(header)

                val osuId =
                    try {
                        tokenVerifier.verify(token)
                    } catch (ex: OsuUnavailableException) {
                        log.warn(ex) { "Unable to verify a token, osu! is unavailable" }
                        // Not using sendError, as the error page would turn this into an unauthorized response
                        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
                        return
                    }

                if (osuId == null) {
                    chain.doFilter(request, response)
                    return
                }

                val user = userService.findUserById(osuId)

                if (user == null) {
                    // Only should end up here if the user that is trying to log in is restricted and never saved in the database
                    chain.doFilter(request, response)
                    return
                }

                val permission = getHighestRoleForUser(user)

                val authentication =
                    UsernamePasswordAuthenticationToken(
                        permission,
                        token,
                        permission.roles.map { SimpleGrantedAuthority("ROLE_$it") },
                    )

                authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = authentication
                chain.doFilter(request, response)
                return
            }
        }

        chain.doFilter(request, response)
        return
    }

    /** Only for headers starting with a scheme and space, so there is always a (possibly empty) token */
    private fun findToken(header: String): String = header.split(" ")[1].trim()

    private fun isAiessToken(token: String): Boolean {
        // Without a configured token nobody can be Aiess, instead of everyone providing an empty token
        if (token.isBlank() || aiess.token.isBlank()) {
            return false
        }

        return MessageDigest.isEqual(aiess.token.toByteArray(), token.toByteArray())
    }
}
