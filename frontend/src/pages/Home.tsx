import type {ReactNode} from "react";
import {useState} from "react";
import {Link, useSearchParams} from "react-router";
import {FaPlus, FaRegComments} from "react-icons/fa6";
import logo from "../assets/greaper.png";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapRow} from "../beatmaps/MapRow";
import type {Beatmap, User} from "../models/Types";
import {usePlanner} from "../resources/planner";
import PageHeader from "../ui/PageHeader";
import {useTitle} from "../ui/useTitle";
import Freshness from "../ui/Freshness";
import {LoginButton, PlannerStatus, Unreachable} from "../ui/States";
import {canEdit, useSession, useSessionUnreachable} from "../utils/session";
import type {Part, Tone} from "../utils/workflow";
import {byOldest, LABELS, OLD_DAYS, splitByAge, stageOf, suggestions, waitingReason} from "../utils/workflow";
import {involvementOf, summaryOf} from "../utils/involvement";
import {usePairings} from "../resources/pairings";
import JoinButton from "../beatmaps/JoinButton";
import {useAddBeatmap} from "../beatmaps/useAddBeatmap";
import {discussionUrl} from "../utils/osuLink";

const GROUPS: {id: Part, title: string, hint: string}[] = [
  {id: "move", title: LABELS.move, hint: "The other nominator is done, or it has to be checked again"},
  {id: "partner", title: "Looking for a second nominator", hint: "You plan to nominate these"},
  {id: "checking", title: LABELS.notNominated, hint: "Nothing is waiting on you yet"},
  {id: "waiting", title: "Waiting for the other nominator", hint: "You already nominated"},
  {id: "qualified", title: "Qualified", hint: "Ranked soon, unless it gets disqualified"}
]

export default function Home() {
  const user = useSession()?.user
  return user ? <Workspace user={user}/> : <Welcome/>
}

function Welcome() {
  // The backend sends the browser to /login?failed when logging in with osu! didn't work out
  const loginFailed = useSearchParams()[0].has("failed")
  const unreachable = useSessionUnreachable()
  // Unreachable sets its own
  useTitle(unreachable ? undefined : "Welcome")
  if (unreachable) return <Unreachable/>
  return (
    <div className="welcome">
      <img src={logo} alt=""/>
      <h1>Nominate together</h1>
      <p>See who plans to nominate which beatmap, and which ones are still looking for a nominator.</p>
      <LoginButton/>
      {loginFailed &&
        <p className="alert alert-error" role="alert">Logging in with osu! did not work. Please try again, and contact Greaper if this keeps happening.</p>
      }
      <p className="muted">
        By <a href="https://osu.ppy.sh/users/2369776">Greaper</a>, for <a href="https://osu.ppy.sh/wiki/en/People/Beatmap_Nominators">Beatmap Nominators</a>
      </p>
    </div>
  )
}

function Workspace({user}: {user: User}) {
  const session = useSession()
  const {maps} = usePlanner()
  const {selected, open, close} = useOpenMap()
  const nominator = canEdit(session)
  const {rankedWith} = usePairings(nominator ? user.osuId : undefined)
  const openAdd = useAddBeatmap()
  const [showOlder, setShowOlder] = useState(false)

  const row = (beatmap: Beatmap, reason?: string, action?: ReactNode, tone?: Tone) => (
    <MapRow key={beatmap.osuId} beatmap={beatmap} selected={selected === beatmap.osuId} onOpen={open} reason={reason} tone={tone}>{action}</MapRow>
  )

  const all = maps ?? []
  const mine = involvementOf(all, user.osuId)
  const groups = GROUPS.map(group => {
    const maps = mine.filter(it => it.part === group.id).map(it => it.map)
    // What waited longest comes first
    return {...group, maps: group.id === "move" ? [...maps].sort(byOldest) : maps}
  })
  const moves = groups[0].maps.length
  const busy = groups.some(it => it.maps.length > 0)
  const mapped = all.filter(it => it.mapper.osuId === user.osuId).sort((a, b) => Number(stageOf(b) === "attention") - Number(stageOf(a) === "attention"))
  // Years old beatmaps of your own stay out of the way until asked for
  const {recent, older} = splitByAge(mapped)
  const ownShown = showOlder ? mapped : recent
  const fits = nominator
    ? suggestions(all, user, rankedWith).slice(0, 6)
    : []

  const troubled = mapped.filter(it => stageOf(it) === "attention").length
  const title = !maps ? "Home"
    : moves > 0 ? `${moves} ${moves === 1 ? "beatmap is" : "beatmaps are"} ready for your nomination`
    : troubled > 0 ? `${troubled} of your beatmaps ${troubled === 1 ? "was" : "were"} disqualified or reset`
    : mapped.length > 0 && !nominator ? `${mapped.length === 1 ? "Your beatmap is" : `Your ${mapped.length} beatmaps are`} on the way`
    : "You're all caught up"

  // In the order of the page, where your own beatmaps come first for mappers. A beatmap can be in several places, it is stepped through once.
  const parts = groups.flatMap(it => it.maps)
  const shown = nominator ? [...parts, ...ownShown, ...fits.map(it => it.map)] : [...ownShown, ...parts]
  const ids = Array.from(new Set(shown.map(it => it.osuId)))

  const own = mapped.length > 0 &&
    <section className="map-group">
      <header><h2>Your own beatmaps</h2><span className="count">{mapped.length}</span><span className="muted">Where they are in the process</span></header>
      {ownShown.length > 0 && <ul className="map-list map-list-actions">{ownShown.map(it => row(it))}</ul>}
      {older.length > 0 &&
        <p className="muted older-toggle">
          {recent.length === 0 && !showOlder && `None of them changed in the last ${OLD_DAYS} days. `}
          <button className="link" aria-expanded={showOlder} onClick={() => setShowOlder(!showOlder)}>
            {showOlder ? "Hide the older ones" : `Show ${older.length} older`}
          </button>
        </p>
      }
    </section>

  return (
    <div className="page">
        <PageHeader title={title} subtitle={<>{summaryOf(mine)} <Freshness/></>}/>
        <PlannerStatus/>

        {!nominator && own}
        {groups.filter(it => it.maps.length > 0).map(({id, title: name, hint, maps: part}) => {
          const heading = <><h2>{name}</h2><span className="count">{part.length}</span><span className="muted">{hint}</span></>
          // Under "Waiting for you" every row would say so again, so they say why instead
          const list = <ul className="map-list map-list-actions">{part.map(it => id === "move"
            ? row(it, waitingReason(it, user.osuId), <a className="button button-tint" href={discussionUrl(it.osuId)} target="_blank" rel="noreferrer" aria-label={`Open the discussion of ${it.title}`}><FaRegComments/>Open discussion</a>, "you")
            : row(it))}</ul>
          // Nothing to do for these, so they stay out of the way
          return (id === "waiting" || id === "qualified") && part.length > 3
            ? <details key={id} className="map-group"><summary>{heading}</summary>{list}</details>
            : <section key={id} className={`map-group part-${id}`}><header>{heading}</header>{list}</section>
        })}

        {nominator && own}

        {fits.length > 0 &&
          <section className="map-group">
            <header><h2>{LABELS.open}</h2><span className="muted">In your modes, those with a nomination first</span></header>
            <ul className="map-list map-list-actions">
              {fits.map(({map, mode, reason}) => row(map, reason, <JoinButton beatmap={map} mode={mode} user={user}/>))}
            </ul>
            <Link to="/discover">Listen to all of them</Link>
          </section>
        }

        {maps && !busy && mapped.length === 0 && fits.length === 0 &&
          <div className="home-empty">
            <p>You have no beatmaps in progress.</p>
            <div className="button-row">
              <Link className="button button-primary" to="/beatmaps?q=">See all beatmaps</Link>
              {nominator && <button className="button" onClick={() => openAdd()}><FaPlus/>Add beatmap</button>}
            </div>
          </div>
        }
      <MapView id={selected} ids={ids} onOpen={open} onClose={close}/>
    </div>
  )
}

