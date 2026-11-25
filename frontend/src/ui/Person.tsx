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
          {modes.length < 4 && modes.map(mode => <ModeIcon key={mode} mode={mode}/>)}
        </span>
      ))}
    </span>
  )
}

export function People({users}: {users: User[]}) {
  const names = users.map(it => it.username).join(", ")
  if (users.length === 0) return <span className="people"/>
  return (
    <span className="people" role="img" aria-label={`Nominators: ${names}`} title={names}>
      {users.slice(0, 4).map(user => <Avatar key={user.osuId} user={user}/>)}
      {users.length > 4 && <span className="people-more">+{users.length - 4}</span>}
    </span>
  )
}
