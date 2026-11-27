package nl.greaper.bnplanner.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.greaper.bnplanner.config.LoginConfig
import nl.greaper.bnplanner.config.LoginProperties
import nl.greaper.bnplanner.service.AuthService
import nl.greaper.bnplanner.service.UserService
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

/**
 * Logging in with osu!, the website sends the browser to /oauth2/authorization/osu?returnTo=/page and gets it back on
 * that page once osu! knows who it is.
 */
class LoginFlow(
    registrations: ClientRegistrationRepository,
    private val config: LoginProperties,
    private val userService: UserService,
    private val authService: AuthService,
) : OAuth2AuthorizationRequestResolver,
    AuthenticationSuccessHandler,
    AuthenticationFailureHandler {
    companion object {
        const val RETURN_TO = "loginReturnTo"

        /** How long a session started by logging in is kept until it is logged in, anybody can start one */
        const val LOGIN_SECONDS = 10 * 60
    }

    private val resolver = DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization")

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? = resolver.resolve(request)?.also { remember(request) }

    override fun resolve(
        request: HttpServletRequest,
        clientRegistrationId: String,
    ): OAuth2AuthorizationRequest =
        // Only null without a registration id
        resolver.resolve(request, clientRegistrationId)!!.also { remember(request) }

    private fun remember(request: HttpServletRequest) {
        val returnTo = request.getParameter("returnTo")

        // Only a page of the website, never somewhere else
        val page = returnTo?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') } ?: "/"
        val isNew = request.getSession(false) == null
        val session = request.session
        session.setAttribute(RETURN_TO, page)

        // Only kept as long as someone logged in is when they finish logging in. One of someone logged in already stays as it is.
        if (isNew) {
            session.maxInactiveInterval = LOGIN_SECONDS
        }
    }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        authService.logLogin(userService.findUserById(authentication.name))

        // Not there when logging in didn't start at the planner, like a callback of an earlier attempt
        val session = request.session
        session.maxInactiveInterval = LoginConfig.SESSION_SECONDS
        val page = session.getAttribute(RETURN_TO) as? String ?: "/"
        session.removeAttribute(RETURN_TO)
        response.sendRedirect(config.websiteUrl.trimEnd('/') + page)
    }

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        // Someone choosing not to log in at osu! isn't an error worth reporting
        if (request.getParameter("error") != "access_denied") {
            authService.logLogin(null, exception)
        }

        response.sendRedirect(config.websiteUrl.trimEnd('/') + "/login?failed")
    }
}
