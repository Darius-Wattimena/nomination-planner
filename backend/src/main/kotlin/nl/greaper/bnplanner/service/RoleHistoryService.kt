package nl.greaper.bnplanner.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.greaper.bnplanner.client.OsuHttpClient
import nl.greaper.bnplanner.datasource.RoleHistoryDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.NominationGroup
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.RoleChangeKind
import nl.greaper.bnplanner.model.RoleChangeSource
import nl.greaper.bnplanner.model.osu.GroupEvent
import nl.greaper.bnplanner.model.osu.GroupEventType
import nl.greaper.bnplanner.model.osu.MeGroup
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Who was in which group that could nominate, and when: from the staff log of the osu! wiki until 27 October 2023, and the group
 * history of osu! since.
 */
@Service
class RoleHistoryService(
    private val dataSource: RoleHistoryDataSource,
    private val osuClient: OsuHttpClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger { }

    private data class StaffLogEntry(
        val userId: String,
        val username: String,
        val group: NominationGroup,
        val kind: RoleChangeKind,
        val at: String,
        val gamemodes: List<String> = emptyList(),
    )

    fun findAll(): List<RoleChange> = dataSource.list()

    /** @return how many changes were not known yet */
    fun saveAll(changes: Collection<RoleChange>): Int = dataSource.saveAll(changes)

    /** The staff log as made by scripts/staff-log.py */
    fun readStaffLog(): List<RoleChange> {
        val json = checkNotNull(javaClass.getResource(STAFF_LOG)) { "$STAFF_LOG is missing" }.readText()

        return objectMapper.readValue<List<StaffLogEntry>>(json).mapNotNull { entry ->
            val at =
                runCatching { LocalDate.parse(entry.at) }.getOrNull() ?: run {
                    log.warn { "Leaving out a change of the staff log with date ${entry.at} (user ${entry.userId})" }
                    return@mapNotNull null
                }
            RoleChange(
                userId = entry.userId,
                username = entry.username,
                group = entry.group,
                kind = entry.kind,
                at = at,
                gamemodes = entry.gamemodes.mapNotNull { mode -> Gamemode.entries.find { it.name == mode } },
                source = RoleChangeSource.StaffLog,
            )
        }
    }

    /** @return the change, null when it is not about a group that could nominate */
    fun toRoleChange(event: GroupEvent): RoleChange? {
        val group = NominationGroup.fromOsuId(event.groupId) ?: return null
        val userId = event.userId ?: return null
        val kind =
            when (event.kind ?: return null) {
                GroupEventType.USER_ADD, GroupEventType.USER_ADD_PLAYMODES -> RoleChangeKind.Added
                GroupEventType.USER_REMOVE, GroupEventType.USER_REMOVE_PLAYMODES -> RoleChangeKind.Removed
            }

        return RoleChange(
            userId = userId,
            username = event.userName ?: "Unknown",
            group = group,
            kind = kind,
            at = event.createdAt.atZone(ZoneOffset.UTC).toLocalDate(),
            gamemodes = event.playmodes.orEmpty().mapNotNull { mode -> Gamemode.entries.find { it.name == mode } },
            source = RoleChangeSource.Osu,
        )
    }

    /**
     * Saves the staff log, and reads the whole group history of osu! of the groups that can nominate
     *
     * @return how many changes were not known yet
     */
    fun importHistory(): Int {
        val staffLog = saveAll(readStaffLog())
        val osu =
            MeGroup.SupportedGroups.sumOf { groupId ->
                var cursor: String? = null
                var pages = 0
                val changes = mutableListOf<RoleChange>()
                do {
                    val page = osuClient.findGroupHistory(groupId, cursor)
                    changes += page.events.mapNotNull { toRoleChange(it) }
                    cursor = page.cursorString
                    pages++
                } while (cursor != null && page.events.isNotEmpty() && pages < MAX_PAGES)
                saveAll(changes)
            }
        log.info { "Saved the role history: $staffLog new changes of the staff log, $osu of osu!" }

        return staffLog + osu
    }

    companion object {
        const val STAFF_LOG = "/history/staff-log.json"
        const val MAX_PAGES = 1_000
    }
}
