import {useState} from "react";
import {Link, useSearchParams} from "react-router";
import {FaArrowLeft, FaArrowUpRightFromSquare, FaMagnifyingGlass} from "react-icons/fa6";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapRow} from "../beatmaps/MapRow";
import {GAMEMODE_NAMES, GAMEMODES, ROLES} from "../Constants";
import type {Gamemode, User, UserRole} from "../models/Types";
import {usePlanner} from "../resources/planner";
import NominatorYear, {NewPairings, NominatorBadges} from "../stats/NominatorYear";
import Cover from "../ui/Cover";
import ModeIcon from "../ui/ModeIcon";
import PageHeader from "../ui/PageHeader";
import {Avatar, RoleBadges} from "../ui/Person";
import {Loading} from "../ui/States";
import OwnModesDialog from "../ui/OwnModes";
import {MODE_WORDS} from "../utils/query";
import {useSession} from "../utils/session";
import {choosesOwnModes, highestRole, modesOf, roleModes} from "../utils/users";
import type {Part} from "../utils/workflow";
import {ageInDays, lookingForNominator} from "../utils/workflow";
import type {Involvement} from "../utils/involvement";
import {involvementOf} from "../utils/involvement";
import {usePairings} from "../resources/pairings";

const TEAM_ROLES: UserRole[] = ["NominationAssessment", "Nominator", "Probation"]
const SHOWN_COVERS = 5

const ORDER: Part[] = ["move", "partner", "checking", "waiting", "qualified"]
const STATE: Record<Part, string> = {
  move: "their turn",
  partner: "needs a second nominator",
  checking: "planned",
  waiting: "waiting for the other nominator",
  qualified: "qualified"
}

interface Member {
  user: User
  /** Everything that still asks for something, the most urgent first */
  inProgress: Involvement[]
  moves: number
  qualified: number
  together: number
}

type Sorting = "free" | "together" | "name"

/** Who you can team up with, what they are on, and where help is needed */
export default function Team() {
  const [params] = useSearchParams()
  const userId = params.get("user") ?? undefined
  return userId ? <Profile key={userId} id={userId}/> : <Overview/>
}

function Overview() {
  const self = useSession()?.user
  const {maps = [], nominators} = usePlanner()
  const {selected, open, close} = useOpenMap()
  // Somebody who follows several modes, or none, starts with everyone
  const [mode, setMode] = useState<Gamemode | undefined>(modesOf(self).length === 1 ? modesOf(self)[0] : undefined)
  const [sorting, setSorting] = useState<Sorting>("free")
  const [role, setRole] = useState<UserRole>()
  const [text, setText] = useState("")

  const {rankedWith} = usePairings(self?.osuId)

  const members = (nominators ?? []).filter(it => highestRole(it, mode) !== "Mapper").map((user): Member => {
    const all = involvementOf(maps, user.osuId)
    return {
      user,
      inProgress: all.filter(it => it.part !== "qualified").sort((a, b) => ORDER.indexOf(a.part) - ORDER.indexOf(b.part) || ageInDays(b.map) - ageInDays(a.map)),
      moves: all.filter(it => it.part === "move").length,
      qualified: all.filter(it => it.part === "qualified").length,
      together: rankedWith(user)
    }
  })
  // Being free is relative, what is much for one team is little for another
  const loads = members.map(it => it.inProgress.length).sort((a, b) => a - b)
  const usual = loads[Math.floor(loads.length / 2)] ?? 0
  const isFree = (it: Member) => it.inProgress.length === 0 || it.inProgress.length < usual

  const shown = members
    .filter(it => !role || highestRole(it.user, mode) === role)
    .filter(it => it.user.username.toLowerCase().includes(text.trim().toLowerCase()))
    .sort((a, b) => sorting === "free" ? a.inProgress.length - b.inProgress.length || b.together - a.together
      : sorting === "together" ? b.together - a.together || a.inProgress.length - b.inProgress.length
      : a.user.username.localeCompare(b.user.username))

  const help = lookingForNominator(maps, mode)

  const loading = !nominators

  return (
    <div className="page">
      <PageHeader title="Nominators" subtitle={members.length > 0 ? `${members.length} nominators for ${mode ? GAMEMODE_NAMES[mode] : "all modes"}. ${help.length} ${help.length === 1 ? "beatmap is" : "beatmaps are"} looking for one` : " "}>
        <div className="segmented" role="group" aria-label="Game mode">
          <button aria-pressed={!mode} onClick={() => setMode(undefined)}>All</button>
          {GAMEMODES.map(it => <button key={it} aria-pressed={mode === it} aria-label={GAMEMODE_NAMES[it]} onClick={() => setMode(it)}><ModeIcon mode={it}/><span className="segmented-text">{GAMEMODE_NAMES[it]}</span></button>)}
        </div>
      </PageHeader>

      <div className="team-layout">
        <div>
          <div className="team-tools">
            <label className="search">
              <FaMagnifyingGlass aria-hidden/>
              <input id="team-search" type="search" aria-label="Find a nominator" placeholder="Find a nominator" value={text} onChange={event => setText(event.target.value)}/>
            </label>
            <div className="chips" role="group" aria-label="Role">
              {TEAM_ROLES.map(it => <button key={it} className="chip" title={ROLES[it].name} aria-pressed={role === it} onClick={() => setRole(role === it ? undefined : it)}>{ROLES[it].short}</button>)}
            </div>
            <label className="team-sort muted">Sort by{" "}
              <select id="team-sort" value={sorting} onChange={event => setSorting(event.target.value as Sorting)}>
                <option value="free">fewest in progress</option>
                <option value="together">ranked with me</option>
                <option value="name">name</option>
              </select>
            </label>
          </div>
          {loading && <Loading/>}
          {!loading && members.length > 0 && shown.length === 0 && <p className="muted team-none">Nobody matches.</p>}
          <ul className="members">
            {shown.map(it => <MemberCard key={it.user.osuId} member={it} mode={mode} free={isFree(it)} self={it.user.osuId === self?.osuId}/>)}
          </ul>
          {shown.some(it => it.inProgress.length > 0) &&
            <p className="legend muted">{ORDER.filter(it => it !== "qualified").map(it => <span key={it} className="legend-item"><span className="covers-key" data-state={it}/>{STATE[it]}</span>)}</p>
          }
        </div>

        {help.length > 0 &&
          <aside className="team-help">
            <header><h2>Looking for a nominator</h2><span className="count">{help.length}</span></header>
            <ul>
              {help.slice(0, 7).map(({map, partner, nominated, full}) => (
                <li key={map.osuId} className="help-item">
                  <span className="map-thumb"><Cover id={map.osuId} size="card"/></span>
                  <span className="map-name">
                    <button className="map-open" onClick={() => open(map.osuId)}>{map.title}</button>
                    <span className="muted">{partner ? nominated ? `${partner.username} already nominated` : `together with ${partner.username}` : "no nominators yet"}{full && ", full nominator needed"}</span>
                  </span>
                </li>
              ))}
            </ul>
            <Link className="button button-ghost" to={`/beatmaps?q=${encodeURIComponent(mode ? `mode:${MODE_WORDS[mode]} is:open` : "is:open")}`}>Show all of them</Link>
          </aside>
        }
      </div>
      <MapView id={selected} ids={help.map(it => it.map.osuId)} onOpen={open} onClose={close}/>
    </div>
  )
}

function MemberCard({member, mode, free, self}: {member: Member, mode?: Gamemode, free: boolean, self: boolean}) {
  const {user, inProgress, moves, qualified, together} = member
  const role = ROLES[highestRole(user, mode)]
  const others = roleModes(user).filter(it => it !== mode)
  const facts = [
    moves > 0 && `${moves} waiting for ${self ? "you" : "them"}`,
    qualified > 0 && `${qualified} qualified`,
    !self && (together > 0 ? `${together} ranked with you` : "never ranked with you")
  ].filter(Boolean)

  return (
    <li className="member" data-self={self || undefined}>
      <div className="member-head">
        <Avatar user={user} size="large"/>
        <div>
          <Link className="member-name" to={`/team?user=${user.osuId}`}>{user.username}</Link>
          <span className="member-roles">
            {mode
              ? <><span className={`role-badge ${role.className}`} title={role.name}>{role.short}</span>{others.map(it => <ModeIcon key={it} mode={it}/>)}</>
              : <RoleBadges user={{...user, gamemodes: user.gamemodes.filter(it => it.role !== "Mapper")}}/>}
          </span>
        </div>
        <span className="member-load" data-free={free || undefined}>{inProgress.length === 0 ? "Nothing planned" : `${inProgress.length} in progress`}</span>
      </div>
      <div className="covers" role="img" aria-label={inProgress.length === 0 ? "Nothing in progress" : `Nominating ${inProgress.slice(0, SHOWN_COVERS).map(it => `${it.map.title}, ${STATE[it.part]}`).join("; ")}`}>
        {inProgress.slice(0, SHOWN_COVERS).map(({map, part}) => (
          <span key={map.osuId} className="covers-map" data-state={part} title={`${map.title}: ${STATE[part]}`}><Cover id={map.osuId} size="card"/></span>
        ))}
        {Array.from({length: Math.max(0, SHOWN_COVERS - inProgress.length)}, (_, index) => <span key={index} className="covers-map covers-empty"/>)}
        {inProgress.length > SHOWN_COVERS && <span className="covers-more">+{inProgress.length - SHOWN_COVERS}</span>}
      </div>
      <p className="member-facts">{facts.join(" · ")}</p>
    </li>
  )
}

function Profile({id}: {id: string}) {
  const self = useSession()?.user
  const {maps = [], nominators = []} = usePlanner()
  const {selected, open, close} = useOpenMap()
  const pairings = usePairings(id)
  const [choosing, setChoosing] = useState(false)

  const isSelf = id === self?.osuId
  const known = maps.flatMap(it => [...it.gamemodes.flatMap(mode => mode.nominators).map(slot => slot.nominator), it.mapper]).find(it => it.osuId === id)
  const user = isSelf ? self : known ?? nominators.find(it => it.osuId === id)

  const own = [...involvementOf(maps, id)]
    .sort((a, b) => ORDER.indexOf(a.part) - ORDER.indexOf(b.part))
    .map(it => it.map)
  const partners = [...(pairings.data ?? [])].sort((a, b) => b.pairingRanked - a.pairingRanked || b.pairingPending - a.pairingPending).slice(0, 8)
  const most = Math.max(1, ...partners.map(it => it.pairingRanked + it.pairingPending))
  const withMe = self && !isSelf ? pairings.pairingWith(self) : undefined

  return (
    <div className="page">
      <Link className="button button-ghost back" to="/team"><FaArrowLeft/>Nominators</Link>
      <header className="profile">
        {user && <Avatar user={user} size="large"/>}
        <div>
          <h1>{user?.username ?? `User ${id}`}</h1>
          {user && <RoleBadges user={user}/>}
          {self && isSelf && choosesOwnModes(self) &&
            <p className="muted own-modes-line">
              {modesOf(self).length > 0 ? <>You follow {modesOf(self).map(it => <ModeIcon key={it} mode={it}/>)}</> : "You don't follow a mode yet"}
              <button className="link" onClick={() => setChoosing(true)}>{modesOf(self).length > 0 ? "Change" : "Choose your modes"}</button>
            </p>
          }
          {withMe && (withMe.pairingRanked > 0 || withMe.pairingPending > 0) &&
            <p className="muted">You ranked {withMe.pairingRanked} together{withMe.pairingPending > 0 && ` and are on ${withMe.pairingPending} now`}</p>
          }
        </div>
        <div className="button-row">
          <Link className="button" to={`/ranked?bn=${id}`}>Ranked beatmaps</Link>
          <a className="icon-button" href={`https://osu.ppy.sh/users/${id}`} target="_blank" rel="noreferrer" aria-label="Open on osu!" title="Open on osu!"><FaArrowUpRightFromSquare/></a>
        </div>
      </header>

      <div className="profile-layout">
        <div>
          <section className="map-group">
            <header><h2>In progress</h2><span className="count">{own.length}</span></header>
            {own.length === 0
              ? <p className="muted">Nothing in progress.</p>
              : <ul className="map-list">{own.map(it => <MapRow key={it.osuId} beatmap={it} selected={selected === it.osuId} onOpen={() => open(it.osuId)}/>)}</ul>
            }
          </section>
          <NominatorYear id={id} self={isSelf}/>
        </div>
        <aside>
          <NominatorBadges id={id}/>
          <section className="map-group">
            <header><h2>Nominates with</h2></header>
            {partners.length > 0 && <p className="legend muted"><span className="key key-check"/>ranked <span className="key key-wait"/>now</p>}
            {pairings.loading && <Loading/>}
            {pairings.error && <p className="muted">Could not load who they nominate with.</p>}
            {pairings.data && partners.length === 0 && <p className="muted">Nobody yet.</p>}
            <ul className="partners">
              {partners.map(it => (
                <li key={it.osuId}>
                  <span>{it.name}</span>
                  <span className="load-text">{it.pairingRanked}{it.pairingPending > 0 && ` + ${it.pairingPending}`}</span>
                  <span className="load" role="img" aria-label={`${it.pairingRanked} ranked, ${it.pairingPending} now`}>
                    <span className="load-check" style={{width: `${it.pairingRanked / most * 100}%`}}/><span className="load-wait" style={{width: `${it.pairingPending / most * 100}%`}}/>
                  </span>
                </li>
              ))}
            </ul>
          </section>
          {self && isSelf && pairings.data && <NewPairings user={self} pairings={pairings.data}/>}
        </aside>
      </div>
      <MapView id={selected} ids={own.map(it => it.osuId)} onOpen={open} onClose={close}/>
      {self && <OwnModesDialog user={self} open={choosing} onClose={() => setChoosing(false)}/>}
    </div>
  )
}
