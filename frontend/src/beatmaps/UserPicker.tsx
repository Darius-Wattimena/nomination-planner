import {useEffect, useState} from "react";
import {FaCheck, FaMagnifyingGlass} from "react-icons/fa6";
import {GAMEMODE_NAMES, ROLES} from "../Constants";
import {Gamemode, User, UserRole} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import Dialog from "../ui/Dialog";
import {ModePicker} from "../ui/ModeIcon";
import {Avatar, RoleBadges} from "../ui/Person";
import {Loading} from "../ui/States";
import {useSession} from "../utils/session";
import {canNominate} from "../utils/users";

const PICKABLE_ROLES: UserRole[] = ["Nominator", "Probation", "NominationAssessment"]

interface UserPickerProps {
  open: boolean
  onClose: () => void
  title: string
  selected: string[]
  onPick: (user: User) => void
  initialModes?: Gamemode[]
  /** Only allow users that can nominate this mode */
  mode?: Gamemode
  /** Allow picking several users, and picking a selected user again to remove it */
  multiple?: boolean
}

export default function UserPicker(props: UserPickerProps) {
  return (
    <Dialog open={props.open} onClose={props.onClose} title={props.title}>
      <Picker {...props}/>
    </Dialog>
  )
}

function Picker({onClose, selected, onPick, initialModes = [], mode, multiple}: UserPickerProps) {
  const self = useSession()?.user
  const [text, setText] = useState("")
  const [username, setUsername] = useState("")
  const [modes, setModes] = useState<Gamemode[]>(mode ? [mode] : initialModes)
  const [roles, setRoles] = useState<UserRole[]>([])
  const {data, loading, error, reload} = useQuery<User[]>(Api.searchUsers({username, gamemodes: modes, roles}))

  useEffect(() => {
    const timer = window.setTimeout(() => setUsername(text.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [text])

  // Always offer yourself first, when matching the filters
  const matchesSelf = self
    && self.username.toLowerCase().includes(username.toLowerCase())
    && self.gamemodes.some(it =>
      (modes.length === 0 || modes.includes(it.gamemode)) && (roles.length === 0 || roles.includes(it.role)))
  const users = data && [...(self && matchesSelf ? [self] : []), ...data.filter(it => it.osuId !== self?.osuId)]

  return (
    <>
      <div className="dialog-body picker">
        <label className="search">
          <FaMagnifyingGlass aria-hidden/>
          <input type="search" autoFocus aria-label="Search users" placeholder="osu! username" value={text} onChange={event => setText(event.target.value)}/>
        </label>
        {!mode && <ModePicker selected={modes} onChange={setModes} label="Game modes"/>}
        <div className="chips" role="group" aria-label="Roles">
          {PICKABLE_ROLES.map(role => {
            const active = roles.includes(role)
            return (
              <button key={role} className="chip" aria-pressed={active} title={ROLES[role].name}
                      onClick={() => setRoles(active ? roles.filter(it => it !== role) : [...roles, role])}>
                {ROLES[role].short}
              </button>
            )
          })}
        </div>
        <ul className="picker-list" aria-busy={loading}>
          {users?.map(user => {
            const isSelected = selected.includes(user.osuId)
            const allowed = !mode || canNominate(user, mode)
            return (
              <li key={user.osuId}>
                <button
                  className="picker-user"
                  aria-pressed={isSelected}
                  disabled={!allowed || (isSelected && !multiple)}
                  onClick={() => {
                    onPick(user)
                    if (!multiple) onClose()
                  }}
                >
                  <Avatar user={user} size="large"/>
                  <span className="picker-name"><strong>{user.username}</strong><RoleBadges user={user}/></span>
                  {isSelected && <FaCheck aria-label="Selected"/>}
                  {!allowed && mode && <span className="muted">Can't nominate {GAMEMODE_NAMES[mode]}</span>}
                </button>
              </li>
            )
          })}
        </ul>
        {!users && loading && <Loading/>}
        {users?.length === 0 && <p className="muted">No users found.</p>}
        {error && <p className="alert alert-error">Could not load the users. <button className="link" onClick={reload}>Try again</button></p>}
      </div>
      {multiple &&
        <footer className="dialog-footer">
          <button className="button button-primary" onClick={onClose}>Done</button>
        </footer>
      }
    </>
  )
}
