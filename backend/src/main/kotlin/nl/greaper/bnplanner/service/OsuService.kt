package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.client.OsuHttpClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class OsuService(
    private val client: OsuHttpClient,
) {
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
}
