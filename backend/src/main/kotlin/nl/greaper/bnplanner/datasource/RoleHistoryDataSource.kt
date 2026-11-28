package nl.greaper.bnplanner.datasource

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.NominationGroup
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.RoleChangeKind
import nl.greaper.bnplanner.model.RoleChangeSource
import org.bson.Document
import org.springframework.stereotype.Component
import java.time.LocalDate

/** Who joined and left the groups that could nominate, each change once */
@Component
class RoleHistoryDataSource(
    database: MongoDatabase,
) : BaseDataSource<RoleChange>(database, "roleHistory") {
    override fun toDocument(value: RoleChange): Document =
        Document("_id", value.key)
            .append("userId", value.userId)
            .append("username", value.username)
            .append("group", value.group.name)
            .append("kind", value.kind.name)
            .append("at", value.at.toString())
            .append("gamemodes", value.gamemodes.map { it.name })
            .append("source", value.source.name)

    override fun fromDocument(document: Document): RoleChange =
        RoleChange(
            userId = document.getString("userId"),
            username = document.getString("username"),
            group = NominationGroup.valueOf(document.getString("group")),
            kind = RoleChangeKind.valueOf(document.getString("kind")),
            at = LocalDate.parse(document.getString("at")),
            gamemodes = document.getList("gamemodes", String::class.java).map { Gamemode.valueOf(it) },
            source = RoleChangeSource.valueOf(document.getString("source")),
        )

    /** @return how many were not known yet */
    fun saveAll(changes: Collection<RoleChange>): Int {
        val unique = changes.associateBy { it.key }
        val known =
            unique.keys
                .chunked(BeatmapDataSource.BATCH_SIZE)
                .flatMap { keys -> collection.find(Filters.`in`("_id", keys)).map { it.getString("_id") }.toList() }
                .toSet()
        val added = unique.filterKeys { it !in known }.values

        added.chunked(BeatmapDataSource.BATCH_SIZE).forEach { batch ->
            collection.bulkWrite(batch.map { ReplaceOneModel(Filters.eq("_id", it.key), toDocument(it), ReplaceOptions().upsert(true)) })
        }

        return added.size
    }
}
