package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import nl.greaper.bnplanner.model.osu.AuthToken
import org.bson.Document
import org.springframework.stereotype.Component

@Component
class OsuTokenDataSource(
    database: MongoDatabase,
) : BaseDataSource<AuthToken>(database, "authToken") {
    override fun toDocument(value: AuthToken): Document =
        Document("token_type", value.token_type)
            .append("expires_in", value.expires_in)
            .append("access_token", value.access_token)
            .append("refresh_token", value.refresh_token)

    override fun fromDocument(document: Document): AuthToken =
        AuthToken(
            token_type = document.getString("token_type"),
            expires_in = (document["expires_in"] as Number).toInt(),
            access_token = document.getString("access_token"),
            refresh_token = document.getString("refresh_token") ?: "",
        )
}
