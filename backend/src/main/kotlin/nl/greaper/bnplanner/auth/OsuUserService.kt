package nl.greaper.bnplanner.auth

import nl.greaper.bnplanner.client.OsuWait
import nl.greaper.bnplanner.service.UserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User

/**
 * Finds out who logged in with osu!, and brings what the planner knows of them up to date.
 *
 * Only the id and name are kept in the session, the roles are looked up again on every request.
 */
class OsuUserService(
    private val userService: UserService,
    private val osu: OAuth2UserService<OAuth2UserRequest, OAuth2User> = DefaultOAuth2UserService(),
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        val osuId = osu.loadUser(request)!!.name
        val user =
            userService.forceFindUserById(request.accessToken.tokenValue, osuId, logEventToDiscord = false, wait = OsuWait.REQUEST)
                // Use what we know when osu! is unavailable
                ?: userService.findUserById(osuId)
                ?: throw OAuth2AuthenticationException(OAuth2Error("unknown_user"), "osu! did not tell who logged in")

        return DefaultOAuth2User(emptyList(), mapOf("id" to user.osuId, "username" to user.username), "id")
    }
}
