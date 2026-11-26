import type {ReactNode} from "react";
import {Link, useLocation} from "react-router";
import logo from "../assets/greaper.png";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapRow} from "../beatmaps/MapRow";
import type {Beatmap, User} from "../models/Types";
import {usePlanner} from "../resources/planner";
import PageHeader from "../ui/PageHeader";
import {Freshness} from "../ui/Help";
import {LoginButton} from "../ui/States";
import {canEdit, useSession} from "../utils/session";
import type {Part} from "../utils/workflow";
import {stageOf, suggestions} from "../utils/workflow";
import {involvementOf} from "../utils/involvement";
import {usePairings} from "../resources/pairings";
import JoinButton from "../beatmaps/JoinButton";
import {PlannerError} from "../ui/States";

const GROUPS: {id: Part, title: string, hint: string}[] = [
  {id: "move", title: "Waiting for your nomination", hint: "The other nominator is done, or it has to be checked again"},
  {id: "partner", title: "Looking for a second nominator", hint: "You plan to nominate these"},
  {id: "checking", title: "Planned", hint: "You didn't nominate yet"},
  {id: "waiting", title: "Waiting for the other nominator", hint: "You already nominated"},
  {id: "qualified", title: "Qualified", hint: "Ranked soon, unless it gets disqualified"}
]

export default function Home() {
  const user = useSession()?.user
  return user ? <Workspace user={user}/> : <Welcome/>
}

function Welcome() {
  const loginFailed = (useLocation().state as {loginFailed?: boolean} | null)?.loginFailed
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
  const {maps, error} = usePlanner()
  const {selected, open, close} = useOpenMap()
  const nominator = canEdit(session)
  const {rankedWith} = usePairings(nominator ? user.osuId : undefined)

  const row = (beatmap: Beatmap, reason?: string, action?: ReactNode) => (
    <MapRow key={beatmap.osuId} beatmap={beatmap} selected={selected === beatmap.osuId} onOpen={() => open(beatmap.osuId)} reason={reason}>{action}</MapRow>
  )

  const all = maps ?? []
  const mine = involvementOf(all, user.osuId)
  const groups = GROUPS.map(group => ({...group, maps: mine.filter(it => it.part === group.id).map(it => it.map)}))
  const moves = groups[0].maps.length
  const busy = groups.some(it => it.maps.length > 0)
  const mapped = all.filter(it => it.mapper.osuId === user.osuId).sort((a, b) => Number(stageOf(b) === "attention") - Number(stageOf(a) === "attention"))
  const fits = nominator
    ? suggestions(all, user, rankedWith).slice(0, 6)
    : []

  // In the order of the page, every beatmap once
  const troubled = mapped.filter(it => stageOf(it) === "attention").length
  const title = !maps ? "Home"
    : moves > 0 ? `${moves} ${moves === 1 ? "beatmap is" : "beatmaps are"} ready for your nomination`
    : troubled > 0 ? `${troubled} of your beatmaps ${troubled === 1 ? "was" : "were"} disqualified or reset`
    : mapped.length > 0 && !nominator ? `${mapped.length === 1 ? "Your beatmap is" : `Your ${mapped.length} beatmaps are`} on the way`
    : "You're all caught up"

  const ids = Array.from(new Set([...(nominator ? [] : mapped), ...groups.flatMap(it => it.maps), ...mapped, ...fits.map(it => it.map)].map(it => it.osuId)))

  const own = mapped.length > 0 &&
    <section className="map-group">
      <header><h2>Your own beatmaps</h2><span className="count">{mapped.length}</span><span className="muted">Where they are in the process</span></header>
      <ul className="map-list">{mapped.map(it => row(it))}</ul>
    </section>

  return (
    <div className="page">
        <PageHeader title={title} subtitle={<>{user.username} <Freshness/></>}/>
        {error && <PlannerError/>}
        {!maps && !error && <ul className="map-list" aria-busy aria-label="Loading">{[0, 1, 2, 3].map(it => <li key={it} className="map-row skeleton"/>)}</ul>}

        {!nominator && own}
        {groups.filter(it => it.maps.length > 0).map(({id, title: name, hint, maps: part}) => {
          const heading = <><h2>{name}</h2><span className="count">{part.length}</span><span className="muted">{hint}</span></>
          const list = <ul className="map-list">{part.map(it => row(it))}</ul>
          // Nothing to do for these, so they stay out of the way
          return (id === "waiting" || id === "qualified") && part.length > 3
            ? <details key={id} className="map-group"><summary>{heading}</summary>{list}</details>
            : <section key={id} className={`map-group part-${id}`}><header>{heading}</header>{list}</section>
        })}

        {nominator && own}

        {fits.length > 0 &&
          <section className="map-group">
            <header><h2>Looking for a nominator</h2><span className="muted">In your modes, those with a nomination first</span></header>
            <ul className="map-list">
              {fits.map(({map, mode, reason}) => row(map, reason, <JoinButton beatmap={map} mode={mode} user={user}/>))}
            </ul>
            <Link to="/discover">Listen to all of them</Link>
          </section>
        }

        {maps && !busy && mapped.length === 0 && fits.length === 0 &&
          <div className="home-empty">
            <p>You have no beatmaps in progress.</p>
            <Link className="button button-primary" to="/beatmaps?q=">See all beatmaps</Link>
          </div>
        }
      <MapView id={selected} ids={ids} onOpen={open} onClose={close}/>
    </div>
  )
}

