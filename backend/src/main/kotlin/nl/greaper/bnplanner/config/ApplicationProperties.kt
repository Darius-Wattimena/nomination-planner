package nl.greaper.bnplanner.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mongodb")
data class MongoProperties(
    val db: String,
    val username: String,
    val password: String,
    val authDb: String,
    val host: String,
    val port: Int,
)

@ConfigurationProperties("osu")
data class OsuProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val baseUrl: String = "https://osu.ppy.sh",
    /** At least this long between two calls to osu! */
    val minRequestIntervalMillis: Long = 250,
)

@ConfigurationProperties("cors")
data class CorsProperties(
    val uris: String,
    val methods: String,
    val headers: String,
)

@ConfigurationProperties("discord")
data class DiscordProperties(
    val token: String,
)

@ConfigurationProperties("login")
data class LoginProperties(
    /** The website, to send someone back to once they logged in with osu! */
    val websiteUrl: String,
    /** Only send the login cookie over https */
    val secureCookie: Boolean = false,
)

@ConfigurationProperties("planner")
data class PlannerProperties(
    /** The osu! ids of the users that may do everything on the planner, whatever their roles on osu! are */
    val admins: List<String> = emptyList(),
)
