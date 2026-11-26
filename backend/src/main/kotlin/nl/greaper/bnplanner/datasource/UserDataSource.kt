package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import org.bson.Document
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class UserDataSource(
    database: MongoDatabase,
) : BaseDataSource<User>(database, "users") {
    // Kept in memory to speed up requests, these are used by several requests at once
    private val users = ConcurrentHashMap<String, User>()

    final val changes = ChangeLog()

    init {
        list().forEach { addInMemoryUser(it) }
    }

    override fun toDocument(value: User): Document =
        Document("_id", value.osuId)
            .append("username", value.username)
            .append("gamemodes", value.gamemodes.map { Document("gamemode", it.gamemode.name).append("role", it.role.name) })
            .append("restricted", value.restricted)

    override fun fromDocument(document: Document): User =
        User(
            osuId = document.getString("_id"),
            username = document.getString("username"),
            gamemodes =
                document.getList("gamemodes", Document::class.java).map {
                    UserGamemode(Gamemode.valueOf(it.getString("gamemode")), Role.valueOf(it.getString("role")))
                },
            restricted = document.getBoolean("restricted"),
        )

    /** Everybody who is known, as it is at this moment */
    fun allUsers(): Collection<User> = users.values.toList()

    fun findUser(osuId: String): User? = users[osuId]

    fun addInMemoryUser(user: User) {
        users[user.osuId] = user
        changes.mark(user.osuId)
    }

    /** Only for tests, which start over with nobody known */
    internal fun forgetInMemoryUsers() = users.clear()

    @Synchronized
    fun saveUser(user: User) {
        collection.replaceOne(Filters.eq("_id", user.osuId), toDocument(user), ReplaceOptions().upsert(true))
        addInMemoryUser(user)
    }
}
