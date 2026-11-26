package nl.greaper.bnplanner.util

import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64

private val mapper = ObjectMapper()

/** Claims read from a token whose signature was not checked */
class UnverifiedClaims(
    private val values: Map<*, *>,
) {
    val subject: String? get() = values["sub"]?.toString()

    operator fun get(name: String): Any? = values[name]
}

/**
 * Read the claims of a token WITHOUT verifying its signature, anyone can make up these claims.
 * Use [nl.greaper.bnplanner.auth.OsuTokenVerifier] to find out who is performing a request.
 * Gives null when the token cannot be read or has expired.
 */
fun parseJwtToken(
    token: String,
    now: Instant = Instant.now(),
): UnverifiedClaims? =
    try {
        val (header, payload) = token.removePrefix("Bearer ").split('.').also { require(it.size == 3) }
        val decoder = Base64.getUrlDecoder()
        require(mapper.readTree(decoder.decode(header)).isObject)

        val claims = UnverifiedClaims(mapper.readValue(decoder.decode(payload), Map::class.java))
        val expires = claims["exp"] as? Number

        // osu! writes the expiry with a fraction of a second
        claims.takeUnless { expires != null && expires.toDouble() * 1000 < now.toEpochMilli() }
    } catch (ex: Exception) {
        null
    }
