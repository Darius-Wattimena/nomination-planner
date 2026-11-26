import {PointerEvent as ReactPointerEvent, useEffect, useRef, useState} from "react";
import {Link} from "react-router";
import {FaArrowUpRightFromSquare, FaHeart, FaPause, FaPlay, FaRotateLeft, FaVolumeHigh, FaVolumeXmark, FaXmark} from "react-icons/fa6";
import {useNominators} from "../beatmaps/actions";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapRow} from "../beatmaps/MapRow";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import {Choice, useChoices} from "../discover/choices";
import {useSongPreview} from "../discover/useSongPreview";
import {Beatmap, Gamemode, Pairing, User} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import {reloadPlanner, usePlanner} from "../resources/planner";
import Cover from "../ui/Cover";
import ModeIcon from "../ui/ModeIcon";
import PageHeader from "../ui/PageHeader";
import {Ring} from "../ui/Ring";
import {EmptyState, Loading} from "../ui/States";
import StatusBadge from "../ui/StatusBadge";
import {canEdit, useSession} from "../utils/session";
import {canNominate} from "../utils/users";
import {ageLabel, suggestions} from "../utils/workflow";

const SWIPE = 110

export default function Discover() {
  const session = useSession()
  if (!session?.user || !canEdit(session)) {
    return <EmptyState title="Discover is for nominators"><Link className="button" to="/">Back to home</Link></EmptyState>
  }
  if (!GAMEMODES.some(it => canNominate(session.user!, it))) {
    return (
      <>
        <PageHeader title="Discover"/>
        <EmptyState title="You can't nominate a mode yet">
          <p className="muted">Discover shows beatmaps that are looking for a nominator in your modes.</p>
          <Link className="button" to="/beatmaps?q=is%3Aopen">See what needs nominators</Link>
        </EmptyState>
      </>
    )
  }
  return <Deck user={session.user}/>
}

function Deck({user}: {user: User}) {
  const {maps, error} = usePlanner()
  const pairings = useQuery<Pairing[]>(Api.fetchPairings(user.osuId))
  const {choices, choose, forget, forgetPasses} = useChoices(user.osuId)
  const {selected: opened, open, close} = useOpenMap()
  const [mode, setMode] = useState<Gamemode>()
  const [currentId, setCurrentId] = useState<string>()
  const [history, setHistory] = useState<string[]>([])

  const ownModes = GAMEMODES.filter(it => canNominate(user, it))

  const rankedWith = (name: string) => pairings.data?.find(it => it.name === name)?.pairingRanked ?? 0
  const available = suggestions(maps ?? [], user, rankedWith, mode)
  const queue = available.filter(it => !choices[it.map.osuId])
  // Background refreshes may reorder the queue, but never the card in front of you
  const current = queue.find(it => it.map.osuId === currentId) ?? queue[0]
  const upcoming = queue.filter(it => it !== current)
  const liked = suggestions(maps ?? [], user, rankedWith).filter(it => choices[it.map.osuId]?.choice === "like")
  const passed = available.filter(it => choices[it.map.osuId]?.choice === "pass").length

  useEffect(() => setCurrentId(current?.map.osuId), [current?.map.osuId])

  const song = useSongPreview(current?.map.osuId, upcoming[0]?.map.osuId)

  function decide(choice: Choice) {
    if (!current) return
    choose(current.map.osuId, choice)
    setHistory([...history, current.map.osuId])
    setCurrentId(upcoming[0]?.map.osuId)
    if (opened) close()
  }

  function undo() {
    const last = history[history.length - 1]
    if (!last) return
    forget(last)
    setHistory(history.slice(0, -1))
    setCurrentId(last)
  }

  return (
    <div className="page">
        <PageHeader title="Discover" subtitle={maps ? queue.length === 0 ? "You heard everything" : `${queue.length} ${queue.length === 1 ? "beatmap" : "beatmaps"} looking for a nominator like you` : " "}>
          {ownModes.length > 1 &&
            <div className="segmented" role="group" aria-label="Game mode">
              <button aria-pressed={!mode} onClick={() => setMode(undefined)}>All</button>
              {ownModes.map(it => (
                <button key={it} aria-pressed={mode === it} aria-label={GAMEMODE_NAMES[it]} onClick={() => setMode(it)}>
                  <ModeIcon mode={it}/><span className="segmented-text">{GAMEMODE_NAMES[it]}</span>
                </button>
              ))}
            </div>
          }
        </PageHeader>

        {error && <p className="alert alert-error" role="alert">Could not refresh the planner. <button className="link" onClick={() => void reloadPlanner()}>Try again</button></p>}
        {!maps && !error && <Loading/>}

        <div className="discover">
          {maps && !current &&
            <EmptyState title="Nothing left to discover">
              <p className="muted">{passed > 0 ? `You skipped ${passed} of them.` : "No beatmap is looking for a nominator in your modes right now."}</p>
              {passed > 0 && <button className="button" onClick={forgetPasses}>Listen to those again</button>}
            </EmptyState>
          }
          {current &&
            <section className="deck" aria-label="Beatmap to consider">
              <Card key={current.map.osuId} map={current.map} mode={current.mode} reason={current.reason} song={song} onDecide={decide} onOpen={() => open(current.map.osuId)}/>
              <div className="deck-actions">
                <button className="deck-button" onClick={() => decide("pass")}><FaXmark/>Skip</button>
                <ClaimButton key={current.map.osuId} map={current.map} mode={current.mode} user={user}/>
                <button className="deck-button deck-like" onClick={() => decide("like")}><FaHeart/>Interested</button>
              </div>
              <div className="deck-foot">
                <button className="button button-ghost" disabled={history.length === 0} onClick={undo}><FaRotateLeft/>Take back</button>
                <label className="volume">
                  {song.volume === 0 ? <FaVolumeXmark aria-hidden/> : <FaVolumeHigh aria-hidden/>}
                  <input id="volume" type="range" min={0} max={1} step={0.05} aria-label="Volume" value={song.volume} onChange={event => song.setVolume(Number(event.target.value))}/>
                </label>
              </div>
              <p className="swipe-hint muted">Swipe right if you're interested, left to skip.</p>
            </section>
          }

          <section className="shortlist">
            <header><h2>Saved for later</h2>{liked.length > 0 && <span className="count">{liked.length}</span>}</header>
            <p className="muted">Only you can see this. It's kept in this browser until you plan to nominate a beatmap or remove it.</p>
            {liked.length === 0
              ? <p className="shortlist-empty">Mark beatmaps as interested to decide on them later.</p>
              : <ul className="map-list">
                  {liked.map(it => (
                    <MapRow key={it.map.osuId} beatmap={it.map} selected={opened === it.map.osuId} reason={it.reason} onOpen={() => open(it.map.osuId)}>
                      <ClaimButton map={it.map} mode={it.mode} user={user} small/>
                      <button className="icon-button" aria-label={`Remove ${it.map.title}`} title="Remove" onClick={() => forget(it.map.osuId)}><FaXmark/></button>
                    </MapRow>
                  ))}
                </ul>
            }
          </section>
        </div>
      <MapView id={opened} onOpen={open} onClose={close}/>
    </div>
  )
}

function ClaimButton({map, mode, user, small}: {map: Beatmap, mode: Gamemode, user: User, small?: boolean}) {
  const {claim} = useNominators(map)
  return (
    <button className={small ? "button button-tint" : "deck-button deck-claim"} aria-label={`I'll nominate ${map.title} for ${GAMEMODE_NAMES[mode]}`} onClick={() => claim(mode, user)}>
      I'll nominate
    </button>
  )
}

interface CardProps {
  map: Beatmap
  mode: Gamemode
  reason: string
  song: ReturnType<typeof useSongPreview>
  onDecide: (choice: Choice) => void
  onOpen: () => void
}

function Card({map, mode, reason, song, onDecide, onOpen}: CardProps) {
  const [drag, setDrag] = useState(0)
  const start = useRef<{x: number, y: number, id: number, sideways: boolean}>(undefined)
  const slots = map.gamemodes.find(it => it.gamemode === mode)
  const others = map.gamemodes.filter(it => it.gamemode !== mode)

  function onDown(event: ReactPointerEvent) {
    if (event.button !== 0 || (event.target as HTMLElement).closest("button, a")) return
    start.current = {x: event.clientX, y: event.clientY, id: event.pointerId, sideways: false}
  }

  function onMove(event: ReactPointerEvent) {
    const from = start.current
    if (!from || from.id !== event.pointerId) return
    const x = event.clientX - from.x
    if (!from.sideways) {
      if (Math.abs(x) < 8 || Math.abs(x) < Math.abs(event.clientY - from.y)) return
      from.sideways = true
      event.currentTarget.setPointerCapture(event.pointerId)
    }
    setDrag(x)
  }

  function onUp() {
    if (!start.current) return
    start.current = undefined
    if (drag > SWIPE) onDecide("like")
    else if (drag < -SWIPE) onDecide("pass")
    else setDrag(0)
  }

  function onCancel() {
    start.current = undefined
    setDrag(0)
  }

  const intent = drag > SWIPE ? "like" : drag < -SWIPE ? "pass" : undefined

  return (
    <article
      className="discover-card"
      data-dragging={drag !== 0 || undefined}
      data-intent={intent}
      style={drag !== 0 ? {translate: `${drag}px 0`, rotate: `${drag / 40}deg`} : undefined}
      onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onCancel}
    >
      <div className="discover-cover">
        <Cover id={map.osuId} size="cover"/>
        <PlayButton song={song} title={map.title}/>
        {intent && <span className="intent">{intent === "like" ? "Interested" : "Skip"}</span>}
      </div>
      <div className="discover-text">
        <h2>{map.title}</h2>
        <p>{map.artist}</p>
        <p className="panel-meta">
          <span>mapped by {map.mapper.username}</span>
          <StatusBadge status={map.status}/>
          {ageLabel(map) && <span>updated {ageLabel(map)}{ageLabel(map) !== "today" && " ago"}</span>}
        </p>
        {slots &&
          <div className="discover-why">
            <Ring beatmap={map} mode={slots} large/>
            <div><strong>{reason}</strong><p className="muted">{GAMEMODE_NAMES[mode]}{others.length > 0 && `, also has ${others.map(it => GAMEMODE_NAMES[it.gamemode]).join(", ")}`}</p></div>
          </div>
        }
        {map.note?.trim() && <p className="discover-note">{map.note}</p>}
        {song.missing && <p className="muted" role="status">osu! has no song preview for this one.</p>}
        <div className="button-row discover-links">
          <button className="button button-ghost" onClick={onOpen}>Details</button>
          <a className="button button-ghost" href={`https://osu.ppy.sh/beatmapsets/${map.osuId}`} target="_blank" rel="noreferrer"><FaArrowUpRightFromSquare/>osu!</a>
        </div>
      </div>
    </article>
  )
}

/** The approach circle closes in on the button while the song plays */
function PlayButton({song, title}: {song: ReturnType<typeof useSongPreview>, title: string}) {
  return (
    <button className="play" disabled={song.missing} aria-label={song.playing ? `Pause ${title}` : `Play ${title}`} onClick={song.toggle}>
      {song.playing && <span className="approach" style={{scale: 1.9 - 0.9 * song.progress}}/>}
      {song.playing ? <FaPause/> : <FaPlay/>}
    </button>
  )
}
