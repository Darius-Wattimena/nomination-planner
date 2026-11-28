package nl.greaper.bnplanner.client

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.auth.LoginExpiredException
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.model.osu.BeatmapSet
import nl.greaper.bnplanner.model.osu.BeatmapSetSearch
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
import java.time.LocalDate

@Component
class OsuHttpClient(
    private val config: OsuProperties,
    private val objectMapper: ObjectMapper,
    sleeper: Sleeper = Sleeper.THREAD,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val log = KotlinLogging.logger { }

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
        wait: OsuWait = OsuWait.BACKGROUND,
    ): ResponseEntity<String> {
        val requestUri = "${config.baseUrl}/api/v2$uri"

        return rateLimit.call("GET $requestUri", wait.maxMillis) {
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

    /**
     * Find a beatmap set, null when osu! doesn't know it
     *
     * @throws LoginExpiredException when osu! no longer accepts the token
     * @throws OsuUnavailableException when osu! gave no clear answer
     */
    fun findBeatmapWithId(
        osuApiToken: String,
        osuId: String,
        wait: OsuWait = OsuWait.BACKGROUND,
    ): BeatmapSet? =
        try {
            val response = get("/beatmapsets/$osuId", osuApiToken, wait)
            objectMapper.readValue<BeatmapSet>(response.body ?: "")
        } catch (ex: HttpClientErrorException.NotFound) {
            log.info { "Beatmap is unknown to the osu api, using id $osuId" }
            null
        } catch (ex: HttpClientErrorException.Unauthorized) {
            throw LoginExpiredException()
        } catch (ex: Exception) {
            throw OsuUnavailableException("Unable to get a beatmap from the osu api, using id $osuId", ex)
        }

    /**
     * Find a user, null when osu! doesn't know them (which is also the case for restricted users)
     *
     * @throws OsuRequestException when osu! gave no clear answer
     */
    fun findUserWithId(
        osuApiToken: String,
        osuId: String,
        wait: OsuWait = OsuWait.BACKGROUND,
    ): Me? =
        try {
            val response = get("/users/$osuId?key=id", osuApiToken, wait)
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
     * A page of all ranked and approved beatmap sets, the oldest ranked first. Without the nominations, only asking for the beatmap
     * set itself gives those. osu! stops after 10,000 results of one search, so a new search starts at a later day.
     *
     * @param since the first day (in UTC) they were ranked on, null for the oldest
     * @param cursor of the page before, null for the first page
     */
    fun searchRankedBeatmapsets(
        osuApiToken: String,
        since: LocalDate?,
        cursor: String?,
    ): BeatmapSetSearch {
        val query = since?.let { "&q=${URLEncoder.encode("ranked>=$it", Charsets.UTF_8)}" }.orEmpty()
        val after = cursor?.let { "&cursor_string=${URLEncoder.encode(it, Charsets.UTF_8)}" }.orEmpty()
        // Built by hand, the cursor must reach osu! exactly as it gave it
        val uri = URI.create("${config.baseUrl}/api/v2/beatmapsets/search?s=ranked&sort=ranked_asc&nsfw=true$query$after")

        val body =
            rateLimit.call("GET $uri") {
                log.debug { "GET ==> $uri" }
                rest
                    .get()
                    .uri(uri)
                    .headers { it.setBearerAuth(osuApiToken) }
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String::class.java)
            }

        return objectMapper.readValue<BeatmapSetSearch>(body ?: "")
    }

    /**
     * A page of the nominations of all beatmap sets since the start of a day, newest first
     *
     * @param since the day (in UTC) of the oldest nomination, osu! may still answer with older ones
     * @param page starts at 1
     */
    fun findNominations(
        osuApiToken: String,
        since: LocalDate,
        page: Int,
    ): BeatmapsetEvents {
        val type = BeatmapsetEventType.NOMINATE.osuName
        val response = get("/beatmapsets/events?types[]=$type&min_date=$since&limit=$EVENTS_PER_PAGE&page=$page", osuApiToken)

        return objectMapper.readValue<BeatmapsetEvents>(response.body ?: "")
    }

    /**
     * A page of the events of one type of all beatmap sets between two days, in no useful order. One type at a time, as osu! takes
     * more than 10 seconds to answer when asked for several types between two days.
     *
     * @param from the first day (in UTC)
     * @param until the last day (in UTC), included
     * @param page starts at 1, fewer than [EVENTS_PER_PAGE] events means it is the last page
     */
    fun findNominationHistory(
        osuApiToken: String,
        from: LocalDate,
        until: LocalDate,
        type: BeatmapsetEventType,
        page: Int,
    ): BeatmapsetEvents {
        val response =
            get("/beatmapsets/events?types[]=${type.osuName}&min_date=$from&max_date=$until&limit=$EVENTS_PER_PAGE&page=$page", osuApiToken)

        return objectMapper.readValue<BeatmapsetEvents>(response.body ?: "")
    }

    /**
     * Up to [USERS_PER_CALL] users at once. Restricted users and ids osu! doesn't know are left out.
     *
     * @throws OsuRequestException when osu! gave no clear answer
     */
    fun findUsersWithIds(
        osuApiToken: String,
        osuIds: Collection<String>,
    ): List<Me> {
        require(osuIds.size <= USERS_PER_CALL) { "osu! gives at most $USERS_PER_CALL users at once" }
        if (osuIds.isEmpty()) {
            return emptyList()
        }

        return try {
            val response = get("/users?${osuIds.joinToString("&") { "ids[]=$it" }}", osuApiToken)
            objectMapper.readValue<Users>(response.body ?: "").users
        } catch (ex: Exception) {
            throw OsuRequestException("Unable to get ${osuIds.size} users from the osu api", ex)
        }
    }

    private data class Users(
        val users: List<Me> = emptyList(),
    )

    /**
     * The ids of everyone in a group, of every gamemode. Not part of the api, the page of the group on the website has them as JSON.
     *
     * @throws OsuUnavailableException when the page has no users, like when osu! changed it
     */
    fun findGroupMembers(groupId: String): List<String> {
        val uri = URI.create("${config.baseUrl}/groups/${URLEncoder.encode(groupId, Charsets.UTF_8)}")

        val body =
            try {
                rateLimit.call("GET $uri") {
                    log.debug { "GET ==> $uri" }
                    rest
                        .get()
                        .uri(uri)
                        .accept(MediaType.TEXT_HTML)
                        .retrieve()
                        .body(String::class.java)
                }
            } catch (ex: Exception) {
                throw OsuUnavailableException("Unable to get the members of group $groupId from osu!", ex)
            }

        val json =
            GROUP_USERS.find(body.orEmpty())?.groupValues?.get(1)
                ?: throw OsuUnavailableException("The page of group $groupId on osu! has no users", null)

        return objectMapper.readValue<List<GroupMember>>(json).map { it.id }
    }

    private data class GroupMember(
        val id: String,
    )

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

    companion object {
        /** The most osu! gives at once */
        const val EVENTS_PER_PAGE = 50

        /** The most users osu! gives at once */
        const val USERS_PER_CALL = 50

        private val GROUP_USERS = Regex("""<script id="json-users" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}

class OsuRequestException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
