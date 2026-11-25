package nl.greaper.bnplanner.model

data class User(
    val osuId: String,
    val username: String,
    val gamemodes: List<UserGamemode>,
    val restricted: Boolean? = null,
)

data class UserGamemode(
    val gamemode: Gamemode,
    val role: Role,
)
