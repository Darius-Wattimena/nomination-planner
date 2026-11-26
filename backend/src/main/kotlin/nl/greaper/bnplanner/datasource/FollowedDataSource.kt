package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.springframework.stereotype.Component

/** The last event handled of everything followed on osu!, so a restart continues where it stopped */
@Component
class FollowedDataSource(
    database: MongoDatabase,
) {
    private val collection by lazy { database.getCollection("followed") }

    fun findLastId(name: String): Long? = collection.find(Filters.eq("_id", name)).first()?.getLong("lastId")

    fun saveLastId(
        name: String,
        id: Long,
    ) {
        collection.replaceOne(Filters.eq("_id", name), Document("_id", name).append("lastId", id), ReplaceOptions().upsert(true))
    }
}
