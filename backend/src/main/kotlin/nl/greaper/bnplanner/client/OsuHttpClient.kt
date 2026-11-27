package nl.greaper.bnplanner.client

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.model.osu.BeatmapsetEvents
import nl.greaper.bnplanner.model.osu.GroupHistory
import nl.greaper.bnplanner.model.osu.Me
import nl.greaper.bnplanner.util.Sleeper
import org.springframework.http.HttpHeaders
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
import java.net.URI
import java.net.URLEncoder

@Component
class OsuHttpClient(
    val config: OsuProperties,
    val objectMapper: ObjectMapper,
    sleeper: Sleeper = Sleeper.THREAD,
    clock: () -> Long = System::currentTimeMillis,
) {
    val log = KotlinLogging.logger { }

    private val rateLimit = OsuRateLimit(config.minRequestIntervalMillis, sleeper, clock)

    // A call that never ends would hold up everything waiting for osu!
    private val rest = withTimeout(10_000)

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
        rateLimit.call("a token") {
            rest
                .post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toEntity<AuthToken>()
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

    /** Ask the api of osu! something on behalf of whoever the token belongs to */
    fun get(
        uri: String,
        osuApiToken: String,
    ): ResponseEntity<String> {
        val requestUri = "${config.baseUrl}/api/v2$uri"

        return rateLimit.call("GET $requestUri") {
            log.debug { "GET ==> $requestUri" }

            // Never share headers between calls, as they would use the token of each other
            rest
                .get()
                .uri(requestUri)
                .headers { it.setBearerAuth(osuApiToken) }
                .retrieve()
                .toEntity<String>()
        }
    }

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
    ): Me? =
        try {
            val response = get("/users/$osuId?key=id", osuApiToken)
            objectMapper.readValue<Me>(response.body ?: "")
        } catch (ex: HttpClientErrorException.NotFound) {
            log.info { "User is unknown to the osu api, using id $osuId" }
            null
        } catch (ex: Exception) {
            throw OsuRequestException("Unable to get a user from the osu api, using id $osuId", ex)
        }

    /**
     * A page of the events of all beatmap sets, newest first, as shown on https://osu.ppy.sh/beatmapsets/events
     *
     * @param page starts at 1
     */
    fun findBeatmapsetEvents(
        osuApiToken: String,
        page: Int,
    ): BeatmapsetEvents {
        val types = BeatmapsetEventType.entries.joinToString("&") { "types[]=${it.osuName}" }
        val response = get("/beatmapsets/events?$types&page=$page", osuApiToken)

        return objectMapper.readValue<BeatmapsetEvents>(response.body ?: "")
    }

    /**
     * A page of who joined or left a group, newest first. Not part of the api, but the website answers with JSON when asked to.
     *
     * @param cursor of the page before, null for the newest
     */
    fun findGroupHistory(
        groupId: String,
        cursor: String?,
    ): GroupHistory {
        val after = cursor?.let { "&cursor_string=${URLEncoder.encode(it, Charsets.UTF_8)}" }.orEmpty()
        val uri = URI.create("${config.baseUrl}/groups/history?group_id=${URLEncoder.encode(groupId, Charsets.UTF_8)}$after")

        val body =
            rateLimit.call("GET $uri") {
                log.debug { "GET ==> $uri" }
                rest
                    .get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String::class.java)
            }

        return objectMapper.readValue<GroupHistory>(body ?: "")
    }
}

class OsuRequestException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
