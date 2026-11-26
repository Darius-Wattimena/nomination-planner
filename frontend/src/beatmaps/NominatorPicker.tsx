import {useEffect, useRef, useState} from "react";
import type {Gamemode, User} from "../models/Types";
import {usePlanner} from "../resources/planner";
import {Avatar, RoleBadges} from "../ui/Person";
import {useSession} from "../utils/session";
import {canNominate, onProbation} from "../utils/users";
import {inProgress} from "../utils/involvement";
import {usePairings} from "../resources/pairings";
import {focusesByItself} from "../utils/phone";

interface NominatorPickerProps {
  mode: Gamemode
  partner?: User
  /** Nobody on probation */
  fullOnly?: boolean
  excluded: string[]
  onPick: (user: User) => void
}

/** Pick a nominator in place, with what matters for the choice: how busy they are and who they work well with */
export default function NominatorPicker({mode, partner, fullOnly, excluded, onPick}: NominatorPickerProps) {
  const self = useSession()?.user
  const {maps = [], nominators} = usePlanner()
  const [text, setText] = useState("")
  const input = useRef<HTMLInputElement>(null)
  const {rankedWith} = usePairings(partner?.osuId)

  // Not on touch screens, where the keyboard would cover the list
  useEffect(() => {
    if (focusesByItself()) input.current?.focus()
  }, [])

  const options = (nominators ?? [])
    .filter(it => it.username.toLowerCase().includes(text.trim().toLowerCase()))
    .filter(it => canNominate(it, mode) && !excluded.includes(it.osuId) && !(fullOnly && onProbation(it, mode)))
    .map(user => ({
      user,
      load: inProgress(maps, user.osuId),
      together: rankedWith(user)
    }))
    .sort((a, b) => Number(b.user.osuId === self?.osuId) - Number(a.user.osuId === self?.osuId) || b.together - a.together || a.load - b.load)

  return (
    <div className="picker">
      {fullOnly && <p className="muted">{partner ? `Only full nominators, as ${partner.username} is on probation.` : "Only full nominators, as this mode needs one nomination."}</p>}
      <input ref={input} type="search" aria-label="Search nominators" placeholder="Search nominators" value={text} onChange={event => setText(event.target.value)}/>
      <ul aria-busy={!nominators}>
        {options.map(({user, load, together}) => (
          <li key={user.osuId}>
            <button className="picker-user" onClick={() => onPick(user)}>
              <Avatar user={user}/>
              <span className="picker-name"><strong>{user.osuId === self?.osuId ? `${user.username} (you)` : user.username}</strong><RoleBadges user={user}/></span>
              <span className="picker-facts">
                <span>{load === 0 ? "nothing planned" : `${load} in progress`}</span>
                {together > 0 && <span>{together} ranked together</span>}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {nominators && options.length === 0 && <p className="muted">Nobody else can nominate this mode.</p>}
    </div>
  )
}
