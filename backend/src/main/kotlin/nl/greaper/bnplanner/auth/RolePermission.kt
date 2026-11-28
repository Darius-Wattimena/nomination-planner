package nl.greaper.bnplanner.auth

import com.fasterxml.jackson.annotation.JsonIgnore
import nl.greaper.bnplanner.model.Role
import java.security.Principal

data class RolePermission(
    val osuRole: Role,
    val roles: Set<String>,
) : Principal {
    @JsonIgnore
    override fun getName(): String = osuRole.name

    companion object {
        const val ADMIN = "ADMIN"

        /** Only for the developers, who may also export and import beatmaps, which can change a lot at once */
        const val DEVELOPER = "DEVELOPER"
        const val EDITOR = "EDITOR"
        const val VIEWER = "VIEWER"
    }
}
