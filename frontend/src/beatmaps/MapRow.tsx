import type {ReactNode} from "react";
import {FaHourglassHalf} from "react-icons/fa6";
import type {Beatmap, User} from "../models/Types";
import Cover from "../ui/Cover";
import {People} from "../ui/Person";
import {Rings} from "../ui/Ring";
import {useSession} from "../utils/session";
import {isMissing, sortedModes} from "../utils/users";
import {ageLabel, isStale, nextStep} from "../utils/workflow";

interface MapProps {
  beatmap: Beatmap
  selected?: boolean
  onOpen: () => void
  /** Replaces what has to happen next */
  reason?: string
  children?: ReactNode
}

function people(beatmap: Beatmap): User[] {
  const users = sortedModes(beatmap).flatMap(it => it.nominators).map(it => it.nominator).filter(it => !isMissing(it))
  return users.filter((user, index) => users.findIndex(it => it.osuId === user.osuId) === index)
}

function Age({beatmap}: {beatmap: Beatmap}) {
  const stale = isStale(beatmap)
  return <span className={stale ? "age age-stale" : "age"} title={stale ? "Nothing happened for a while" : "Last update"}>{stale && <FaHourglassHalf aria-label="Gone quiet"/>}{ageLabel(beatmap)}</span>
}

export function MapRow({beatmap, selected, onOpen, reason, children}: MapProps) {
  const userId = useSession()?.user?.osuId
  const step = nextStep(beatmap, userId)
  return (
    <li className="map-row" data-map={beatmap.osuId} aria-current={selected || undefined}>
      <span className="map-thumb"><Cover id={beatmap.osuId} size="card"/></span>
      <span className="map-name">
        <button className="map-open" onClick={onOpen}>{beatmap.title}</button>
        <span className="muted">{beatmap.artist} · {beatmap.mapper.username}</span>
      </span>
      <Rings beatmap={beatmap}/>
      {reason ? <span className="step step-calm">{reason}</span> : <span className={`step step-${step.tone}`}>{step.text}</span>}
      <People users={people(beatmap)}/>
      <Age beatmap={beatmap}/>
      {children && <span className="map-action">{children}</span>}
    </li>
  )
}

export function MapCard({beatmap, selected, onOpen}: MapProps) {
  const userId = useSession()?.user?.osuId
  const step = nextStep(beatmap, userId)
  return (
    <li className="map-card cover-card" data-map={beatmap.osuId} aria-current={selected || undefined}>
      <span className="map-thumb"><Cover id={beatmap.osuId} size="card"/></span>
      <span className="cover-text">
        <span>{beatmap.artist}</span>
        <button className="map-open" onClick={onOpen}>{beatmap.title}</button>
        <em>Mapped by {beatmap.mapper.username}</em>
      </span>
      <span className={`step step-${step.tone}`}>{step.text}</span>
      <span className="map-foot"><Rings beatmap={beatmap}/><People users={people(beatmap)}/><Age beatmap={beatmap}/></span>
    </li>
  )
}
