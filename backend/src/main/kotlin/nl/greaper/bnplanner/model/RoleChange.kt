package nl.greaper.bnplanner.model

import com.fasterxml.jackson.annotation.JsonIgnore
import nl.greaper.bnplanner.model.osu.MeGroup
import java.time.LocalDate

/** The groups of osu! whose members could nominate (or bubble) beatmaps over the years, and the NAT */
enum class NominationGroup {
    /** Beatmap Appreciation Team, and the Beatmap Approval Team before it */
    BAT,

    /** Mapping Assistance Team */
    MAT,

    /** Quality Assurance Team */
    QAT,

    /** Beatmap Nominators */
    BN,

    /** Probationary Beatmap Nominators */
    PBN,

    /** Nomination Assessment Team */
    NAT,
    ;

    companion object {
        fun fromOsuId(groupId: String): NominationGroup? =
            when (groupId) {
                MeGroup.BN -> BN
                MeGroup.PBN -> PBN
                MeGroup.NAT -> NAT
                else -> null
            }
    }
}

enum class RoleChangeKind {
    Added,
    Removed,
}

enum class RoleChangeSource {
    /** The staff log of the osu! wiki, until 27 October 2023 */
    StaffLog,

    /** The group history of osu!, since 27 October 2023 */
    Osu,
}

/** Someone joining or leaving a group, or the gamemodes they have in it */
data class RoleChange(
    val userId: String,
    /** As they were called back then */
    val username: String,
    val group: NominationGroup,
    val kind: RoleChangeKind,
    val at: LocalDate,
    /** The gamemodes that were added or removed, empty when not known */
    val gamemodes: List<Gamemode> = emptyList(),
    val source: RoleChangeSource,
) {
    /** The same change is only kept once */
    @get:JsonIgnore
    val key: String
        get() = "$source:$userId:$group:$kind:$at:${gamemodes.joinToString(",")}"
}
