package nl.greaper.bnplanner.client

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.Me
import nl.greaper.bnplanner.model.osu.OsuOAuth
import nl.greaper.bnplanner.model.osu.RefreshToken
import nl.greaper.bnplanner.util.shouldSkipUser
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.toEntity
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class OsuHttpClient(
    val config: OsuProperties,
    val objectMapper: ObjectMapper,
) {
    val log = KotlinLogging.logger { }

    // A call that never ends would hold up everything waiting for osu!
    private val rest = withTimeout(10_000)

    // Checking who someone is happens while they wait for the planner
    private val verifyRest = withTimeout(5_000)

    private fun withTimeout(millis: Int): RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(millis)
                    setReadTimeout(millis)
                },
            ).build()

    private val tokenUri = "${config.baseUrl}/oauth/token"

    private fun postToken(body: Any): ResponseEntity<AuthToken> =
        rest
            .post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .toEntity<AuthToken>()

    /**
     * Get a token from the osu server
     */
    fun getToken(code: String): ResponseEntity<AuthToken>? {
        val osuOAuth =
            OsuOAuth(
                client_id = config.clientId.toInt(),
                client_secret = config.clientSecret,
                code = code,
                grant_type = "authorization_code",
                redirect_uri = config.redirectUri,
            )

        return try {
            postToken(osuOAuth)
        } catch (error: Throwable) {
            log.error(error) { "Could not get auth token" }
            null
        }
    }

    /** A token of the planner itself, without any user, which can only read what is public */
    fun findClientToken(): AuthToken? {
        val body =
            mapOf(
                "client_id" to config.clientId.toInt(),
                "client_secret" to config.clientSecret,
                "grant_type" to "client_credentials",
                "scope" to "public",
            )

        return try {
            postToken(body).body
        } catch (ex: RestClientException) {
            log.error(ex) { "Could not get a token of the planner itself" }
            null
        }
    }

    fun refreshToken(refreshToken: String): ResponseEntity<AuthToken>? {
        val preparedRefreshToken =
            RefreshToken(
                grant_type = "refresh_token",
                client_id = config.clientId.toInt(),
                client_secret = config.clientSecret,
                refresh_token = refreshToken,
            )

        return try {
            postToken(preparedRefreshToken)
        } catch (ex: HttpClientErrorException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.info { "[UNAUTHORIZED] User trying to login via their refresh token, but it isn't valid" }
                null
            } else {
                throw ex
            }
        }
    }

    /**
     * Ask osu! who the owner of a token is, null when osu! does not accept the token
     *
     * @throws OsuUnavailableException when osu! gave no clear answer
     */
    fun findTokenOwnerId(token: String): String? {
        // Never share these headers between calls, as this decides who the user is
        return try {
            verifyRest
                .get()
                .uri("${config.baseUrl}/api/v2/me")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .body(Me::class.java)
                ?.id
        } catch (ex: HttpClientErrorException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED || ex.statusCode == HttpStatus.FORBIDDEN) {
                null
            } else {
                throw OsuUnavailableException(ex)
            }
        } catch (ex: RestClientException) {
            throw OsuUnavailableException(ex)
        }
    }

    fun get(
        uri: String,
        osuApiToken: String,
    ): ResponseEntity<String> = request(uri, HttpMethod.GET, osuApiToken)

    fun findBeatmapWithId(
        osuApiToken: String,
        osuId: String,
    ): BeatmapSet? {
        return try {
            val response = get("/beatmapsets/$osuId", osuApiToken)
            return response.body?.let { objectMapper.readValue<BeatmapSet>(it) }
        } catch (ex: Exception) {
            log.error(ex) { "Unable to get a beatmap from the osu api, using id $osuId" }
            null
        }
    }

    /**
     * Find a user, null when osu! doesn't know them (which is also the case for restricted users)
     *
     * @throws OsuRequestException when osu! gave no clear answer
     */
    fun findUserWithId(
        osuApiToken: String,
        osuId: String,
    ): Me? {
        if (shouldSkipUser(osuId)) {
            return null
        }

        return try {
            val response = get("/users/$osuId?key=id", osuApiToken)
            objectMapper.readValue<Me>(response.body ?: "")
        } catch (ex: HttpClientErrorException.NotFound) {
            log.info { "User is unknown to the osu api, using id $osuId" }
            null
        } catch (ex: Exception) {
            throw OsuRequestException("Unable to get a user from the osu api, using id $osuId", ex)
        }
    }

    internal fun request(
        uri: String,
        method: HttpMethod,
        authToken: String,
        body: String = "",
    ): ResponseEntity<String> {
        val requestUri = "${config.baseUrl}/api/v2$uri"

        log.info { "$method ==> $requestUri" }

        // Never share headers between calls, as they would use the token of each other
        val request =
            rest
                .method(method)
                .uri(requestUri)
                .headers { it.setBearerAuth(authToken) }

        if (body != "") {
            request.body(body)
        }

        return request.retrieve().toEntity<String>()
    }
}

class OsuRequestException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
