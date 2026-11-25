package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import nl.greaper.bnplanner.model.UserRecalculate
import org.bson.Document
import org.springframework.stereotype.Component

@Component
class UserRecalculateDataSource(
    database: MongoDatabase,
) : BaseDataSource<UserRecalculate>(database, "usersRecalculate") {
    override fun toDocument(value: UserRecalculate): Document = Document("_id", value.osuId)

    override fun fromDocument(document: Document): UserRecalculate = UserRecalculate(document.getString("_id"))
}
