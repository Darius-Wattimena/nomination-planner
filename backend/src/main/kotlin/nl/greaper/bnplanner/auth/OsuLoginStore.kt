package nl.greaper.bnplanner.auth

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import java.util.Date

/**
 * The osu! tokens of everyone who logged in, one set per user, shared by all their devices.
 *
 * Kept in MongoDB, so a restart or another instance of the backend can still ask osu! something on their behalf.
 */
class OsuLoginStore(
    database: MongoDatabase,
    private val registrations: ClientRegistrationRepository,
) : OAuth2AuthorizedClientService {
    private val collection by lazy { database.getCollection("osuLogins") }

    @Suppress("UNCHECKED_CAST")
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
        clientRegistrationId: String,
        principalName: String,
    ): T? {
        val registration = registrations.findByRegistrationId(clientRegistrationId) ?: return null
        val document = collection.find(Filters.eq("_id", principalName)).first() ?: return null

        val accessToken =
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                document.getString("accessToken"),
                document.getDate("issuedAt").toInstant(),
                document.getDate("expiresAt").toInstant(),
                document.getList("scopes", String::class.java).toSet(),
            )
        val refreshToken = document.getString("refreshToken")?.let { OAuth2RefreshToken(it, null) }

        return OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken) as T
    }

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication,
    ) {
        val accessToken = authorizedClient.accessToken
        val document =
            Document("_id", principal.name)
                .append("accessToken", accessToken.tokenValue)
                .append("issuedAt", Date.from(accessToken.issuedAt))
                .append("expiresAt", Date.from(accessToken.expiresAt))
                .append("scopes", accessToken.scopes.sorted())
                .append("refreshToken", authorizedClient.refreshToken?.tokenValue)

        collection.replaceOne(Filters.eq("_id", principal.name), document, ReplaceOptions().upsert(true))
    }

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principalName: String,
    ) {
        collection.deleteOne(Filters.eq("_id", principalName))
    }
}
