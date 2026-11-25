import {ROLES} from "../Constants";
import {Gamemode, User, UserRole} from "../models/Types";
import {avatarUrl} from "../utils/users";
import ModeIcon from "./ModeIcon";

export function Avatar({user, size = "small"}: {user: User, size?: "small" | "large"}) {
  return <img className={`avatar avatar-${size}`} src={avatarUrl(user.osuId)} alt="" loading="lazy"/>
}

export function RoleBadges({user}: {user: User}) {
  const byRole = new Map<UserRole, Gamemode[]>()
  user.gamemodes.forEach(({role, gamemode}) => byRole.set(role, [...(byRole.get(role) ?? []), gamemode]))

  return (
    <span className="role-badges">
      {Array.from(byRole).map(([role, modes]) => (
        <span key={role} className={`role-badge ${ROLES[role].className}`} title={ROLES[role].name}>
          {ROLES[role].short}
          {modes.map(mode => <ModeIcon key={mode} mode={mode}/>)}
        </span>
      ))}
    </span>
  )
}
