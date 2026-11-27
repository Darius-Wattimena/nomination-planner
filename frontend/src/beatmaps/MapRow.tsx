import type {ReactNode} from "react";
import {memo} from "react";
import {FaHourglassHalf, FaRegNoteSticky} from "react-icons/fa6";
import type {Beatmap, User} from "../models/Types";
import Cover from "../ui/Cover";
import {People} from "../ui/Person";
import {Rings} from "../ui/Ring";
import {useSession} from "../utils/session";
import {useToday} from "../utils/today";
import {displayName, isMissing, sortedModes} from "../utils/users";
import type {Tone} from "../utils/workflow";
import {ageLabel, exactTime, isStale, LABELS, nextStep, notePreview} from "../utils/workflow";

interface MapProps {
  beatmap: Beatmap
  selected?: boolean
  /** Gets the id, so the same function can be handed to every row */
  onOpen: (id: string) => void
  /** Replaces what has to happen next */
  reason?: string
  /** The color of the reason */
  tone?: Tone
  children?: ReactNode
}

function people(beatmap: Beatmap): User[] {
  const users = sortedModes(beatmap).flatMap(it => it.nominators).map(it => it.nominator).filter(it => !isMissing(it))
  return users.filter((user, index) => users.findIndex(it => it.osuId === user.osuId) === index)
}

/** Renders again by itself when the day changes, the row around it doesn't have to */
function Age({beatmap}: {beatmap: Beatmap}) {
  const today = useToday()
  const stale = isStale(beatmap, today)
  const when = exactTime(beatmap.dateUpdated)
  return <span className={stale ? "age age-stale" : "age"} title={stale ? `Nothing happened for a while, last change ${when}` : `Last change ${when}`}>{stale && <FaHourglassHalf aria-label={LABELS.stale}/>}{ageLabel(beatmap, today)}</span>
}

/** Shows there is a note, and its start when you point at it */
function NoteMark({beatmap}: {beatmap: Beatmap}) {
  const note = notePreview(beatmap.note)
  if (!note) return null
  return <span className="note-mark" role="img" aria-label={`Note: ${note}`} title={note}><FaRegNoteSticky/></span>
}

/** Rows only render again when their beatmap changed, which matters for long lists that update live */
export const MapRow = memo(function MapRow({beatmap, selected, onOpen, reason, tone = "calm", children}: MapProps) {
  const userId = useSession()?.user?.osuId
  const step = nextStep(beatmap, userId)
  return (
    <li className="map-row" data-map={beatmap.osuId} aria-current={selected || undefined}>
      <span className="map-thumb"><Cover id={beatmap.osuId} size="card"/></span>
      <span className="map-name">
        <button className="map-open" onClick={() => onOpen(beatmap.osuId)}>{beatmap.title}</button>
        <span className="muted"><NoteMark beatmap={beatmap}/>{beatmap.artist} · {displayName(beatmap.mapper)}</span>
      </span>
      <Rings beatmap={beatmap}/>
      {reason ? <span className={`step step-${tone}`}>{reason}</span> : <span className={`step step-${step.tone}`}>{step.text}</span>}
      <People users={people(beatmap)}/>
      <Age beatmap={beatmap}/>
      {children && <span className="map-action">{children}</span>}
    </li>
  )
})

export const MapCard = memo(function MapCard({beatmap, selected, onOpen}: MapProps) {
  const userId = useSession()?.user?.osuId
  const step = nextStep(beatmap, userId)
  return (
    <li className="map-card cover-card" data-map={beatmap.osuId} aria-current={selected || undefined}>
      <span className="map-thumb"><Cover id={beatmap.osuId} size="card"/></span>
      <span className="cover-text">
        <span>{beatmap.artist}</span>
        <button className="map-open" onClick={() => onOpen(beatmap.osuId)}>{beatmap.title}</button>
        <em><NoteMark beatmap={beatmap}/>Mapped by {displayName(beatmap.mapper)}</em>
      </span>
      <span className={`step step-${step.tone}`}>{step.text}</span>
      <span className="map-foot"><Rings beatmap={beatmap}/><People users={people(beatmap)}/><Age beatmap={beatmap}/></span>
    </li>
  )
})
