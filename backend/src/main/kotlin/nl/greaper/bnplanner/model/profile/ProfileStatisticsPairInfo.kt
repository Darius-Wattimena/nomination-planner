package nl.greaper.bnplanner.model.profile

data class ProfileStatisticsPairInfo(
    val osuId: String,
    val name: String,
    val pairingPending: Int,
    val pairingRanked: Int,
    val pairingGraved: Int,
)
