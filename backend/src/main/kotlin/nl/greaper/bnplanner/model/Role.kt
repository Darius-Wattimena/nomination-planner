package nl.greaper.bnplanner.model

import nl.greaper.bnplanner.model.osu.MeGroup

enum class Role {
    Mapper,
    Nominator,
    Probation,
    NominationAssessment,
    ;

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
