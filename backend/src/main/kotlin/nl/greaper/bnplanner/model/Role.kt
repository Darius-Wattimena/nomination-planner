package nl.greaper.bnplanner.model

import nl.greaper.bnplanner.model.osu.MeGroup

enum class Role {
    Mapper,

    /** Was in a group that could nominate in this gamemode, or nominated a ranked beatmap of it. May only view, like a mapper. */
    Former,
    Nominator,
    Probation,
    NominationAssessment,
    ;

    /** In a group that can nominate right now, only these roles give more than viewing */
    val canNominate: Boolean
        get() = this == Nominator || this == Probation || this == NominationAssessment

    companion object {
        fun fromOsuId(osuId: String): Role =
            when (osuId) {
                MeGroup.NAT -> NominationAssessment
                MeGroup.BN -> Nominator
                MeGroup.PBN -> Probation
                else -> Mapper
            }
    }
}
