package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.discord.DiscordEventListener
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

@Component
@ConditionalOnProperty(prefix = "discord", name = ["enabled"], havingValue = "true")
class DiscordEventListenerDataSource(
    database: MongoDatabase,
) : BaseDataSource<DiscordEventListener>(database, "discord") {
    // Read by the threads sending messages while the bot changes it
    private val listeners = CopyOnWriteArrayList(this.list())

    override fun toDocument(value: DiscordEventListener): Document =
        Document("guildId", value.guildId)
            .append("channelId", value.channelId)
            .append("gamemode", value.gamemode?.name)
            .append("confidential", value.confidential)

    override fun fromDocument(document: Document): DiscordEventListener =
        DiscordEventListener(
            guildId = document.getString("guildId"),
            channelId = document.getString("channelId"),
            gamemode = document.getString("gamemode")?.let { Gamemode.valueOf(it) },
            confidential = document.getBoolean("confidential", false),
        )

    private fun addInMemoryListener(listener: DiscordEventListener) {
        listeners.add(listener)
    }

    private fun removeInMemoryListener(listener: DiscordEventListener) {
        listeners.remove(listener)
    }

    fun getListeners() = listeners.toList()

    fun findByGuildId(guildId: String): List<DiscordEventListener> =
        listeners.filter {
            it.guildId == guildId
        }

    fun findByGuildIdAndChannelId(
        guildId: String,
        channelId: String,
    ): DiscordEventListener? =
        listeners.firstOrNull {
            it.guildId == guildId && it.channelId == channelId
        }

    fun create(newListener: DiscordEventListener): InsertOneResult {
        addInMemoryListener(newListener)
        return insertOne(newListener)
    }

    fun replace(
        oldListener: DiscordEventListener,
        newListener: DiscordEventListener,
    ): UpdateResult {
        removeInMemoryListener(oldListener)
        addInMemoryListener(newListener)

        return collection.replaceOne(findChannel(oldListener), toDocument(newListener))
    }

    fun remove(listener: DiscordEventListener): DeleteResult {
        removeInMemoryListener(listener)
        return collection.deleteOne(findChannel(listener))
    }

    private fun findChannel(listener: DiscordEventListener): Bson =
        Filters.and(Filters.eq("guildId", listener.guildId), Filters.eq("channelId", listener.channelId))
}
