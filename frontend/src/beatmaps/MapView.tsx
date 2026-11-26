import type {TouchEvent as ReactTouchEvent} from "react";
import {useRef, useState} from "react";
import Modal from "react-modal";
import {FaArrowUpRightFromSquare, FaCheck, FaChevronLeft, FaChevronRight, FaLink, FaPlus, FaRegComments, FaXmark} from "react-icons/fa6";
import {GAMEMODE_NAMES, GAMEMODES, MISSING_USER_ID, ROLES} from "../Constants";
import type {Beatmap, BeatmapGamemode, Gamemode, User} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import Api from "../resources/Api";
import {request} from "../resources/client";
import {deleteBeatmap, putBeatmap, refreshBeatmap, useBeatmap, usePlanner} from "../resources/planner";
import Cover from "../ui/Cover";
import {ConfirmDialog} from "../ui/Dialog";
import {RingLegend} from "../ui/Help";
import {Avatar, RoleBadges} from "../ui/Person";
import {Ring} from "../ui/Ring";
import {EmptyState, Loading} from "../ui/States";
import StatusBadge from "../ui/StatusBadge";
import {useToast} from "../ui/useToast";
import {canEdit, isAdmin, useSession} from "../utils/session";
import {canJoin, doneNominations, isHybrid, lacksFullNominator, needsFullNominator, neededNominations, openSpot, roleOf, spots} from "../utils/nominations";
import {highestRole, isMissing, onProbation, sortedModes} from "../utils/users";
import {ageLabel, nextStep, toDate} from "../utils/workflow";
import {inProgress} from "../utils/involvement";
import {useNominators} from "./actions";
import NominatorPicker from "./NominatorPicker";
import Note from "./Note";

interface MapViewProps {
  id: string | undefined
  /** The beatmaps of the page, to step through without closing */
  ids?: string[]
  onOpen: (id: string) => void
  onClose: () => void
  onChanged?: () => void
}

/** Lies over the page instead of next to it, so the page stays exactly as you left it */
export default function MapView({id, ids = [], onOpen, onClose, onChanged}: MapViewProps) {
  const index = id ? ids.indexOf(id) : -1
  const previous = index > 0 ? ids[index - 1] : undefined
  const next = index >= 0 ? ids[index + 1] : undefined

  // Swipe sideways to go to the next one
  const touch = useRef<{x: number, y: number}>(undefined)
  function onTouchStart(event: ReactTouchEvent) {
    const target = event.target as HTMLElement
    touch.current = event.touches.length === 1 && !target.closest("input, textarea, select, .inspector") ? {x: event.touches[0].clientX, y: event.touches[0].clientY} : undefined
  }
  function onTouchEnd(event: ReactTouchEvent) {
    const from = touch.current
    touch.current = undefined
    if (!from) return
    const x = event.changedTouches[0].clientX - from.x
    const y = event.changedTouches[0].clientY - from.y
    const to = x < 0 ? next : previous
    // Starting at the edge is going back in the browser
    if (to && Math.abs(x) > 70 && Math.abs(x) > 2 * Math.abs(y) && from.x > 24 && from.x < window.innerWidth - 24) onOpen(to)
  }

  return (
    <Modal isOpen={!!id} onRequestClose={onClose} contentLabel="Beatmap" className="map-view" overlayClassName="overlay overlay-view">
      <div className="map-view-bar">
        {index >= 0 && ids.length > 1 &&
          <div className="stepper">
            <button className="icon-button" aria-label="Previous beatmap" disabled={!previous} onClick={() => previous && onOpen(previous)}><FaChevronLeft/></button>
            <span>{index + 1} of {ids.length}</span>
            <button className="icon-button" aria-label="Next beatmap" disabled={!next} onClick={() => next && onOpen(next)}><FaChevronRight/></button>
          </div>
        }
        <button className="icon-button" aria-label="Close" onClick={onClose}><FaXmark/></button>
      </div>
      {id && <div className="map-swipe" onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}><Content key={id} id={id} onClose={onClose} onChanged={onChanged}/></div>}
    </Modal>
  )
}

function Content({id, onClose, onChanged}: {id: string, onClose: () => void, onChanged?: () => void}) {
  const {beatmap, loading, error} = useBeatmap(id)
  if (beatmap) return <Details beatmap={beatmap} onClose={onClose} onChanged={onChanged}/>
  if (loading) return <Loading/>
  return (
    <EmptyState title={error ? "Could not load this beatmap" : "Not on the planner"}>
      <p className="muted">{error ? "Check your connection and try again." : "It may have been removed."}</p>
    </EmptyState>
  )
}

interface Slot {
  mode: Gamemode
  index: number
}

function Details({beatmap, onClose, onChanged}: {beatmap: Beatmap, onClose: () => void, onChanged?: () => void}) {
  const session = useSession()
  const [slot, setSlot] = useState<Slot>()
  const editable = canEdit(session)
  const step = nextStep(beatmap, session?.user?.osuId)
  const modes = sortedModes(beatmap)
  const absent = GAMEMODES.filter(mode => !modes.some(it => it.gamemode === mode))
  const ranked = toDate(beatmap.dateRanked)
  const age = ageLabel(beatmap)

  // Somebody can nominate or a mode can disappear while a slot is selected
  const selectedMode = slot && modes.find(it => it.gamemode === slot.mode)
  const selected = slot && !selectedMode?.nominators[slot.index]?.hasNominated ? slot : undefined

  return (
    <article className="map-details">
      <header className="map-hero">
        <Cover id={beatmap.osuId} size="cover"/>
        <div className="map-hero-text">
          <h2>{beatmap.title}</h2>
          <p>{beatmap.artist}</p>
        </div>
      </header>
      <div className="map-facts">
        <StatusBadge status={beatmap.status}/>
        <span className="with-avatar"><Avatar user={beatmap.mapper}/>{beatmap.mapper.username}</span>
        {ranked ? <span>ranked {ranked.toLocaleDateString()}</span> : age && <span>updated {age}{age !== "today" && " ago"}</span>}
        <Links beatmap={beatmap}/>
      </div>

      <div className="map-body">
        <section className="map-grid" aria-label="Nominators">
          <div className="map-progress">
            <span className={`step step-${step.tone}`}>{step.text}</span>
            <span className="muted">{doneNominations(beatmap)} of {neededNominations(beatmap)} nominations</span>
          </div>
          {modes.map(mode => (
            <ModeRow
              key={mode.gamemode}
              beatmap={beatmap}
              mode={mode}
              editable={editable}
              selected={selected?.mode === mode.gamemode ? selected.index : undefined}
              onSelect={index => setSlot(selected?.mode === mode.gamemode && selected.index === index ? undefined : {mode: mode.gamemode, index})}
            />
          ))}
          <RingLegend/>
          {editable && absent.length > 0 &&
            <div className="mode-add">
              <span className="muted">Add a mode</span>
              {absent.map(mode => (
                <button key={mode} className="chip" aria-pressed={selected?.mode === mode} onClick={() => setSlot({mode, index: 0})}><FaPlus/>{GAMEMODE_NAMES[mode]}</button>
              ))}
            </div>
          }
        </section>

        <aside className="map-side">
          {selected &&
            <SlotInspector key={`${selected.mode}-${selected.index}`} beatmap={beatmap} slot={selected} mode={selectedMode} onClose={() => setSlot(undefined)}/>
          }
          <div className="map-side-main" data-covered={selected ? true : undefined}>
            <Note beatmap={beatmap} editable={editable}/>
            {editable && <Manage beatmap={beatmap} onClose={onClose} onChanged={onChanged}/>}
          </div>
        </aside>
      </div>
    </article>
  )
}

function Links({beatmap}: {beatmap: Beatmap}) {
  const toast = useToast()
  const url = `https://osu.ppy.sh/beatmapsets/${beatmap.osuId}`
  return (
    <span className="map-links">
      <a className="icon-button" href={url} target="_blank" rel="noreferrer" aria-label="Open on osu!" title="Open on osu!"><FaArrowUpRightFromSquare/></a>
      <a className="icon-button" href={`${url}/discussion`} target="_blank" rel="noreferrer" aria-label="Open the discussion" title="Open the discussion"><FaRegComments/></a>
      <button className="icon-button" aria-label="Copy the link" title="Copy the link" onClick={() => {
        navigator.clipboard.writeText(url).then(() => toast("Link copied"), () => toast("Could not copy the link.", {kind: "error"}))
      }}><FaLink/></button>
    </span>
  )
}

interface ModeRowProps {
  beatmap: Beatmap
  mode: BeatmapGamemode
  editable: boolean
  selected: number | undefined
  onSelect: (index: number) => void
}

function ModeRow({beatmap, mode, editable, selected, onSelect}: ModeRowProps) {
  const user = useSession()?.user
  const {join} = useNominators(beatmap)
  const joinable = editable && canJoin(beatmap, mode, user)
  const needsFull = needsFullNominator(beatmap, mode)
  const firstOpen = openSpot(beatmap, mode)?.index
  const shown = spots(beatmap, mode)
  const role = roleOf(beatmap, mode)
  const mine = mode.nominators.some(it => it.nominator.osuId === user?.osuId)

  return (
    <div className="mode-row" data-mine={mine || undefined}>
      <div className="mode-name"><Ring beatmap={beatmap} mode={mode} large/><span>{GAMEMODE_NAMES[mode.gamemode]}{isHybrid(beatmap) && role !== "other" && <span className="muted">{role === "main" ? "Main mode" : "Maybe main"}</span>}</span></div>
      {shown.map(({nominator, hasNominated, index, optional}) => {
        const missing = isMissing(nominator)
        const role = ROLES[highestRole(nominator, mode.gamemode)]
        const content = missing
          ? <span className="cell-text"><span className="muted">{optional ? "Only if it's the main mode" : needsFull ? "Full nominator needed" : "Open spot"}</span></span>
          : <>
              <Avatar user={nominator} size="large"/>
              <span className="cell-text">
                <strong>{nominator.username}</strong>
                {hasNominated ? <span className="nominated"><FaCheck/>Nominated</span> : <span className={`role-badge ${role.className}`}>{role.short}</span>}
              </span>
            </>
        return (
          <div key={index} className="cell" data-open={missing || undefined} data-done={hasNominated || undefined} data-selected={selected === index || undefined}>
            {editable && !hasNominated
              ? <button className="cell-main" aria-pressed={selected === index}
                        aria-label={`${GAMEMODE_NAMES[mode.gamemode]}: ${missing ? "add a nominator" : `change ${nominator.username}`}`}
                        onClick={() => onSelect(index)}>{content}</button>
              : <div className="cell-main">{content}</div>
            }
            {missing && joinable && index === firstOpen &&
              <button className="button button-primary" aria-label={`I'll nominate ${GAMEMODE_NAMES[mode.gamemode]}`} onClick={() => user && join(mode.gamemode, user)}>I'll nominate</button>
            }
          </div>
        )
      })}
      {lacksFullNominator(beatmap, mode) && beatmap.status !== BeatmapStatus.Ranked && <p className="mode-warning">{shown.length > 1 ? "Both are on probation. One of them has to be a full nominator." : "On probation. This mode needs a full nominator."}</p>}
    </div>
  )
}

interface SlotInspectorProps {
  beatmap: Beatmap
  slot: Slot
  /** Missing when the mode is yet to be added */
  mode?: BeatmapGamemode
  onClose: () => void
}

/** Everything about a single slot, so the grid doesn't have to repeat it for each of them */
function SlotInspector({beatmap, slot, mode, onClose}: SlotInspectorProps) {
  const {maps = []} = usePlanner()
  const {assign, remove} = useNominators(beatmap)
  const root = useRef<HTMLElement>(null)
  const person: User | undefined = mode?.nominators[slot.index]?.nominator
  const current = person && !isMissing(person) ? person : undefined
  const other = mode?.nominators[1 - slot.index]?.nominator
  const partner = other && !isMissing(other) ? other : undefined
  const fullOnly = !!mode && (partner ? onProbation(partner, slot.mode) : roleOf(beatmap, mode) === "other")

  return (
    <section className="inspector" ref={root} aria-label="Selected spot" onKeyDown={event => {
      // Only leaves the slot, not the beatmap
      if (event.key === "Escape") {
        event.stopPropagation()
        onClose()
      }
    }}>
      <header>
        <h3>{!mode ? `Add ${GAMEMODE_NAMES[slot.mode]}` : GAMEMODE_NAMES[slot.mode]}</h3>
        <button className="icon-button" aria-label="Close" onClick={onClose}><FaXmark/></button>
      </header>
      {current &&
        <div className="inspector-person">
          <Avatar user={current} size="large"/>
          <div><strong>{current.username}</strong><RoleBadges user={current}/><span className="muted">{inProgress(maps, current.osuId)} in progress</span></div>
          <button className="button" onClick={() => {
            remove(slot.mode, current)
            onClose()
          }}>Remove</button>
        </div>
      }
      <p className="muted">{current ? "Replace with" : "Who will nominate?"}{partner && ` (together with ${partner.username})`}</p>
      <NominatorPicker
        mode={slot.mode}
        partner={partner}
        fullOnly={fullOnly}
        excluded={mode?.nominators.map(it => it.nominator.osuId) ?? []}
        onPick={picked => {
          assign(slot.mode, person?.osuId ?? MISSING_USER_ID, picked)
          onClose()
        }}
      />
    </section>
  )
}

function Manage({beatmap, onClose, onChanged}: {beatmap: Beatmap, onClose: () => void, onChanged?: () => void}) {
  const session = useSession()
  const toast = useToast()
  const [confirming, setConfirming] = useState<"status" | "delete">()
  const [syncing, setSyncing] = useState(false)
  const graved = beatmap.status === BeatmapStatus.Graved
  const newStatus = graved ? BeatmapStatus.Pending : BeatmapStatus.Graved

  async function sync() {
    setSyncing(true)
    try {
      await request(Api.syncBeatmap(beatmap.osuId))
      await refreshBeatmap(beatmap.osuId)
      onChanged?.()
      toast("Up to date with osu!")
    } catch {
      toast("Could not sync with osu!.", {kind: "error"})
    } finally {
      setSyncing(false)
    }
  }

  async function finish(action: () => Promise<unknown>, done: string, failed: string) {
    try {
      await action()
      setConfirming(undefined)
      onClose()
      onChanged?.()
      toast(done)
    } catch {
      toast(failed, {kind: "error"})
    }
  }

  return (
    <section className="map-manage">
      <button className="button button-ghost" disabled={syncing} onClick={sync}>{syncing ? "Syncing…" : "Sync with osu!"}</button>
      {isAdmin(session) && <button className="button button-ghost" onClick={() => setConfirming("status")}>{graved ? "Back to pending" : "Graveyard"}</button>}
      <button className="button button-danger-ghost" onClick={() => setConfirming("delete")}>Delete</button>

      <ConfirmDialog
        open={confirming === "status"} onClose={() => setConfirming(undefined)}
        title={graved ? "Back to pending?" : "Move to the graveyard?"} action={graved ? "Back to pending" : "Move to graveyard"}
        onConfirm={() => finish(async () => {
          await request(Api.updateBeatmapStatus(beatmap.osuId, newStatus))
          putBeatmap({...beatmap, status: newStatus})
        }, graved ? "Back in progress" : "Moved to the graveyard", "Could not change the status.")}
      >
        <p><b>{beatmap.title}</b> changes from {beatmap.status} to {newStatus}.</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming === "delete"} onClose={() => setConfirming(undefined)} title="Delete this beatmap?" action="Delete beatmap" danger
        onConfirm={() => finish(() => deleteBeatmap(beatmap.osuId), "Beatmap deleted", "Could not delete the beatmap.")}
      >
        <p><b>{beatmap.title}</b> and its planning disappear from the planner. This can't be undone.</p>
      </ConfirmDialog>
    </section>
  )
}
