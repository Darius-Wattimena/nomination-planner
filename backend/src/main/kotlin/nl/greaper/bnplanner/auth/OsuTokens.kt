package nl.greaper.bnplanner.auth

import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService

/** The osu! token of someone who logged in, renewed with osu! once it is about to expire */
class OsuTokens(
    private val manager: OAuth2AuthorizedClientManager,
    private val store: OAuth2AuthorizedClientService,
) {
    companion object {
        const val REGISTRATION_ID = "osu"
    }

    // osu! replaces the refresh token when it is used, so only one request of a user may renew at a time
    private val locks = Array(64) { Any() }

    /**
     * @throws LoginExpiredException when osu! no longer accepts the login, they have to log in again
     * @throws OsuUnavailableException when osu! couldn't renew the token for another reason
     */
    fun find(osuId: String): String =
        synchronized(locks[Math.floorMod(osuId.hashCode(), locks.size)]) {
            val request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID).principal(osuId).build()

            val client =
                try {
                    manager.authorize(request)
                } catch (ex: ClientAuthorizationException) {
                    // A refused login is removed, keep it when osu! was only unreachable
                    if (store.loadAuthorizedClient<Nothing>(REGISTRATION_ID, osuId) == null) {
                        throw LoginExpiredException()
                    }
                    throw OsuUnavailableException(ex)
                }

            client?.accessToken?.tokenValue ?: throw LoginExpiredException()
        }
}

/** Someone performing a request, with their osu! token to ask osu! something on their behalf */
class OsuLogin(
    val osuId: String,
    findToken: () -> String,
) {
    /** Only asked for when needed, as it may have to be renewed with osu! */
    val accessToken: String by lazy(findToken)
}

class LoginExpiredException : RuntimeException("The osu! login expired")

class OsuUnavailableException(
    cause: Throwable,
) : RuntimeException("Could not reach osu!", cause)
