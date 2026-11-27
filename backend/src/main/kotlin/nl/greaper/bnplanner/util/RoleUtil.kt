package nl.greaper.bnplanner.util

import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User

fun getHighestRole(osuRoles: Set<Role>): Role =
    when {
        Role.NominationAssessment in osuRoles -> Role.NominationAssessment
        Role.Nominator in osuRoles -> Role.Nominator
        Role.Probation in osuRoles -> Role.Probation
        else -> Role.Mapper
    }

fun getRolePermissions(osuRole: Role): RolePermission {
    val permissions =
        when (osuRole) {
            Role.Mapper -> setOf(RolePermission.VIEWER)
            Role.Nominator, Role.Probation -> setOf(RolePermission.VIEWER, RolePermission.EDITOR)
            Role.NominationAssessment -> setOf(RolePermission.ADMIN, RolePermission.VIEWER, RolePermission.EDITOR)
        }

    return RolePermission(osuRole, permissions)
}

/** @param admins the osu! ids of the users that may do everything, whatever their roles on osu! are */
fun getHighestRoleForUser(
    user: User?,
    admins: Collection<String> = emptyList(),
): RolePermission {
    if (user == null) {
        return getRolePermissions(Role.Mapper)
    }

    if (user.osuId in admins) {
        return getRolePermissions(Role.NominationAssessment)
    }

    val userRoles = user.gamemodes.map { it.role }.toSet()
    val userRole = getHighestRole(userRoles)

    return getRolePermissions(userRole)
}
