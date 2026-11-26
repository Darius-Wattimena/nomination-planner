package nl.greaper.bnplanner.auth

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** Gives a controller who performs the request, only for requests that need someone to be logged in */
class OsuLoginResolver(
    private val tokens: OsuTokens,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == OsuLogin::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): OsuLogin {
        val login = SecurityContextHolder.getContext().authentication!!

        // Logged in with an osu! token of the previous website, which comes with the request itself
        (login.principal as? OsuLogin)?.let { return it }

        return OsuLogin(login.name) { tokens.find(login.name) }
    }
}
