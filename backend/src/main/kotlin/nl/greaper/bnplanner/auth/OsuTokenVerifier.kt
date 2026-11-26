package nl.greaper.bnplanner.auth

import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.config.OsuProperties
import nl.greaper.bnplanner.util.parseJwtToken
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies that an osu! access token is genuine and was issued for the planner.
 *
 * We can't verify the signature ourselves, so a token is only trusted after osu! accepted it. The answer of osu! is
 * remembered for a short time, which is also how long a revoked token keeps working.
 */
@Component
class OsuTokenVerifier(
    private val osuHttpClient: OsuHttpClient,
    private val config: OsuProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        val VALID_DURATION: Duration = Duration.ofMinutes(5)
        val INVALID_DURATION: Duration = Duration.ofMinutes(1)
        const val MAX_CACHED_TOKENS = 10_000
    }

    private data class Verification(
        val osuId: String?,
        val until: Instant,
    )

    private val verifications = ConcurrentHashMap<String, Verification>()

    // A page performs several calls at once, make sure that only one of them has to ask osu!
    private val locks = Array(64) { Any() }

    /**
     * Find the id of the user the token belongs to, or null when the token can't be trusted
     *
     * @throws OsuUnavailableException when osu! couldn't tell if the token is valid
     */
    fun verify(token: String): String? {
        // Everything in the token is untrusted until osu! confirmed it, only use it to reject tokens early
        val claims = parseJwtToken(token) ?: return null
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: return null

        if (!isIssuedForPlanner(claims["aud"])) {
            return null
        }

        val key = hash(token)
        findVerification(key)?.let { return it.osuId }

        return synchronized(locks[Math.floorMod(key.hashCode(), locks.size)]) {
            val known = findVerification(key)
            if (known != null) {
                known.osuId
            } else {
                // The claims are genuine when osu! accepts the token, as they are part of what osu! signed
                val osuId = osuHttpClient.findTokenOwnerId(token)?.takeIf { it == subject }
                remember(key, osuId)
                osuId
            }
        }
    }

    private fun isIssuedForPlanner(audience: Any?): Boolean =
        when (audience) {
            is Collection<*> -> audience.any { it?.toString() == config.clientId }
            null -> false
            else -> audience.toString() == config.clientId
        }

    private fun findVerification(key: String): Verification? = verifications[key]?.takeIf { clock.instant().isBefore(it.until) }

    private fun remember(
        key: String,
        osuId: String?,
    ) {
        val now = clock.instant()

        if (verifications.size >= MAX_CACHED_TOKENS) {
            verifications.values.removeIf { !now.isBefore(it.until) }

            if (verifications.size >= MAX_CACHED_TOKENS) {
                verifications.clear()
            }
        }

        verifications[key] = Verification(osuId, now.plus(if (osuId != null) VALID_DURATION else INVALID_DURATION))
    }

    // Avoid keeping usable tokens in memory
    private fun hash(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
