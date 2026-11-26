package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.model.UserContext
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.util.getHighestRoleForUser
import nl.greaper.bnplanner.util.parseJwtToken
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class OsuService(
    private val client: OsuHttpClient,
    private val userService: UserService,
) {
    private val log = KotlinLogging.logger { }

    fun getAuthTokenByRefreshToken(refreshToken: String): AuthToken? {
        val response = client.refreshToken(refreshToken)
        val body = response?.body

        if (response != null) {
            check(response.statusCode.is2xxSuccessful && body != null) { "Couldn't get token from osu" }
        }

        return body
    }

    private var clientToken: Pair<String, Instant>? = null

    /** Kept until shortly before it expires */
    @Synchronized
    fun findClientToken(): String? {
        val known = clientToken?.takeIf { it.second.isAfter(Instant.now()) }

        if (known != null) {
            return known.first
        }

        val token = client.findClientToken() ?: return null
        clientToken = token.access_token to Instant.now().plusSeconds(token.expires_in.toLong()).minus(5, ChronoUnit.MINUTES)

        return token.access_token
    }

    fun getUserContextByToken(token: AuthToken): UserContext? {
        val tokenExpires =
            Instant
                .now()
                .plusSeconds(token.expires_in.toLong())
                .minus(5, ChronoUnit.MINUTES)

        val claims = parseJwtToken(token.access_token) ?: return null

        val osuId = claims.subject ?: return null
        val user =
            userService.forceFindUserById(token.access_token, osuId, logEventToDiscord = false)
                // Use what we know when osu! is unavailable
                ?: userService.findUserById(osuId)
                ?: return null

        return UserContext(user, token.access_token, token.refresh_token, tokenExpires.toEpochMilli(), getHighestRoleForUser(user))
    }
}
