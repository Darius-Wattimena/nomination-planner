package nl.greaper.bnplanner.model

data class User(
    val osuId: String,
    val username: String,
    val gamemodes: List<UserGamemode>,
    val restricted: Boolean? = null,
    /** Modes chosen by the user themselves, for someone without a role in a mode. Never gives permission */
    val interests: List<Gamemode>? = null,
)

data class UserGamemode(
    val gamemode: Gamemode,
    val role: Role,
)
