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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForEntity
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class OsuHttpClient(
    val config: OsuProperties,
    val objectMapper: ObjectMapper,
) {
    val log = KotlinLogging.logger { }

    private val rest = RestTemplate()
    private val authRest = RestTemplate()
    internal val verifyRest =
        RestTemplate(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(5_000)
                setReadTimeout(5_000)
            },
        )
    private val authHeaders = HttpHeaders()
    private val tokenUri = "${config.baseUrl}/oauth/token"

    init {
        authHeaders.contentType = MediaType.APPLICATION_JSON
        authHeaders.accept = listOf(MediaType.APPLICATION_JSON)
    }

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

        val body = objectMapper.writeValueAsString(osuOAuth)
        val request = HttpEntity(body, authHeaders)

        return try {
            authRest.postForEntity(tokenUri, request)
        } catch (error: Throwable) {
            log.error(error) { "Could not get auth token" }
            null
        }
    }

    /** A token of the planner itself, without any user, which can only read what is public */
    fun findClientToken(): AuthToken? {
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "client_id" to config.clientId.toInt(),
                    "client_secret" to config.clientSecret,
                    "grant_type" to "client_credentials",
                    "scope" to "public",
                ),
            )

        return try {
            authRest.postForEntity<AuthToken>(tokenUri, HttpEntity(body, authHeaders)).body
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

        val body = objectMapper.writeValueAsString(preparedRefreshToken)
        val request = HttpEntity(body, authHeaders)

        return try {
            authRest.postForEntity(tokenUri, request)
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
        val request = HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(token) })

        return try {
            val response = verifyRest.exchange("${config.baseUrl}/api/v2/me", HttpMethod.GET, request, Me::class.java)
            response.body?.id
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
        includeBearer: Boolean,
    ): ResponseEntity<String> = request(uri, HttpMethod.GET, osuApiToken, includeBearer = includeBearer)

    fun findBeatmapsByMapper(
        mapper: Int,
        osuApiToken: String,
        includeBearer: Boolean = true,
    ): List<BeatmapSet> {
        return try {
            val response = get("/users/$mapper/beatmapsets/ranked", osuApiToken, includeBearer)
            return response.body?.let { objectMapper.readValue<List<BeatmapSet>>(it) } ?: emptyList()
        } catch (ex: Exception) {
            log.error(ex) { "Unable to get a beatmap from the osu api" }
            emptyList()
        }
    }

    fun findBeatmapWithId(
        osuApiToken: String,
        osuId: String,
        includeBearer: Boolean = true,
    ): BeatmapSet? {
        return try {
            val response = get("/beatmapsets/$osuId", osuApiToken, includeBearer)
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
        includeBearer: Boolean = true,
    ): Me? {
        if (shouldSkipUser(osuId)) {
            return null
        }

        return try {
            val response = get("/users/$osuId?key=id", osuApiToken, includeBearer)
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
        includeBearer: Boolean,
    ): ResponseEntity<String> {
        // Never share these headers between calls, as they would use the token of each other
        val headers = HttpHeaders()
        if (includeBearer) {
            headers.setBearerAuth(authToken)
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, authToken)
        }

        val request =
            if (body == "") {
                HttpEntity(headers)
            } else {
                HttpEntity(body, headers)
            }
        val requestUri = "${config.baseUrl}/api/v2$uri"

        log.info { "$method ==> $requestUri" }

        return rest.exchange(requestUri, method, request, String::class.java)
    }
}

class OsuRequestException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
