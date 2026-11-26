import {useMemo, useState} from "react";
import {useNavigate} from "react-router";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import {Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {Table} from "./Columns";
import {Link, network} from "./stats";

type Network = ReturnType<typeof network>
type Person = Network["people"][number]

const WIDTH = 1000
const HEIGHT = 600

/** Every mode has its own part of the sky */
const HOME: Record<Gamemode, Point> = {
  [Gamemode.Osu]: {x: -230, y: -120},
  [Gamemode.Taiko]: {x: 230, y: -120},
  [Gamemode.Catch]: {x: -230, y: 120},
  [Gamemode.Mania]: {x: 230, y: 120}
}

const CLASSES: Record<Gamemode, string> = {
  [Gamemode.Osu]: "in-osu",
  [Gamemode.Taiko]: "in-taiko",
  [Gamemode.Catch]: "in-catch",
  [Gamemode.Mania]: "in-mania"
}

interface Point {
  x: number
  y: number
}

const radius = (count: number, most: number) => 6 + 13 * Math.sqrt(count / most)

/** The modes of a person, as parts of one */
function shares({modes}: Person): {mode: Gamemode, part: number}[] {
  const total = GAMEMODES.reduce((sum, mode) => sum + (modes[mode] ?? 0), 0)
  return GAMEMODES.filter(mode => modes[mode]).map(mode => ({mode, part: modes[mode]! / total}))
}

/** People are drawn to their modes, so who nominates several ends up in between. Always gives the same result. */
function layout({people, links}: Network): Map<string, Point> {
  const most = Math.max(1, ...people.map(it => it.count))
  const strongest = Math.max(1, ...links.map(it => it.count))
  const index = new Map(people.map((it, position) => [it.user.osuId, position]))
  const homes = people.map(it => shares(it).reduce((sum, {mode, part}) =>
    ({x: sum.x + HOME[mode].x * part, y: sum.y + HOME[mode].y * part}), {x: 0, y: 0}))
  // Start around home on a sunflower, with the most active closest to it
  const points = people.map((_, position) => {
    const distance = 14 * Math.sqrt(position + .5), angle = position * 2.39996
    return {x: homes[position].x + distance * Math.cos(angle), y: homes[position].y + distance * Math.sin(angle)}
  })

  for (let round = 0; round < 320; round++) {
    const heat = 14 * (1 - round / 320) + .2
    const forces = points.map((point, position) => ({x: (homes[position].x - point.x) * .06, y: (homes[position].y - point.y) * .06}))
    for (let a = 0; a < points.length; a++) {
      for (let b = a + 1; b < points.length; b++) {
        const dx = points[a].x - points[b].x, dy = points[a].y - points[b].y
        const distance = Math.max(1, Math.hypot(dx, dy))
        const room = radius(people[a].count, most) + radius(people[b].count, most) + 34
        const push = 1500 / (distance * distance) + (distance < room ? (room - distance) * .4 : 0)
        forces[a].x += dx / distance * push
        forces[a].y += dy / distance * push
        forces[b].x -= dx / distance * push
        forces[b].y -= dy / distance * push
      }
    }
    links.forEach(link => {
      const a = index.get(link.a)!, b = index.get(link.b)!
      const dx = points[b].x - points[a].x, dy = points[b].y - points[a].y
      const distance = Math.max(1, Math.hypot(dx, dy))
      const pull = (distance - 80) * .02 * Math.sqrt(link.count / strongest)
      forces[a].x += dx / distance * pull
      forces[a].y += dy / distance * pull
      forces[b].x -= dx / distance * pull
      forces[b].y -= dy / distance * pull
    })
    points.forEach((point, position) => {
      const force = Math.max(.01, Math.hypot(forces[position].x, forces[position].y))
      const step = Math.min(force, heat)
      point.x += forces[position].x / force * step
      point.y += forces[position].y / force * step
    })
  }

  // Use all of the available space, with room for the names
  const xs = points.map(it => it.x), ys = points.map(it => it.y)
  const left = Math.min(...xs), right = Math.max(...xs), top = Math.min(...ys), bottom = Math.max(...ys)
  const fit = Math.min((WIDTH - 180) / Math.max(1, right - left), (HEIGHT - 110) / Math.max(1, bottom - top), 2.2)
  return new Map(people.map((it, position) => [it.user.osuId, {
    x: WIDTH / 2 + (points[position].x - (left + right) / 2) * fit,
    y: HEIGHT / 2 - 8 + (points[position].y - (top + bottom) / 2) * fit
  }]))
}

/** The most active ones, without names on top of each other */
function readableNames({people}: Network, points: Map<string, Point>) {
  const most = Math.max(1, ...people.map(it => it.count))
  const boxes: {left: number, right: number, top: number, bottom: number}[] = []
  const named = new Set<string>()
  const perMode = new Map<Gamemode, number>()
  people.forEach(person => {
    const {user, count} = person
    const mode = [...shares(person)].sort((a, b) => b.part - a.part)[0].mode
    const point = points.get(user.osuId)!
    const half = user.username.length * 4.6 + 4, top = point.y + radius(count, most) + 4
    const box = {left: point.x - half, right: point.x + half, top, bottom: top + 18}
    const crossed = boxes.some(it => box.left < it.right && it.left < box.right && box.top < it.bottom && it.top < box.bottom)
      // Nor on top of somebody else
      || people.some(other => {
        const it = points.get(other.user.osuId)!, size = radius(other.count, most)
        return other.user.osuId !== user.osuId && it.x + size > box.left && it.x - size < box.right && it.y + size > box.top && it.y - size < box.bottom
      })
    if (crossed || (perMode.get(mode) ?? 0) >= 8) return
    perMode.set(mode, (perMode.get(mode) ?? 0) + 1)
    boxes.push(box)
    named.add(user.osuId)
  })
  return named
}

/** Everybody is a star in the colors of their modes, the lines are the beatmaps they ranked together */
export default function Constellation({data}: {data: Network}) {
  const navigate = useNavigate()
  const [active, setActive] = useState<string>()
  const [picked, setPicked] = useState<Gamemode>()
  const points = useMemo(() => layout(data), [data])
  const most = Math.max(1, ...data.people.map(it => it.count))
  const strongest = Math.max(1, ...data.links.map(it => it.count))
  // Nearly everyone worked together once, so only the strongest lines are drawn until somebody is selected
  const drawn = new Set(data.links.slice(0, Math.max(40, data.people.length * 2)))
  const nameOf = (id: string) => data.people.find(it => it.user.osuId === id)?.user.username ?? ""

  const present = GAMEMODES.filter(mode => data.people.some(it => it.modes[mode]))
  const bridges = data.people.filter(it => shares(it).length > 1).length
  const own: Link[] = active ? data.links.filter(it => it.a === active || it.b === active) : []
  const close = new Set(own.flatMap(it => [it.a, it.b]))
  const person = data.people.find(it => it.user.osuId === active)
  // Names of everyone would be unreadable, the rest follows on hover
  const named = useMemo(() => readableNames(data, points), [data, points])

  return (
    <figure className="chart constellation">
      <div className="chips constellation-legend" role="group" aria-label="Highlight a mode">
        {present.map(mode => (
          <button key={mode} className={`chip ${CLASSES[mode]}`} aria-label={GAMEMODE_NAMES[mode]} aria-pressed={picked === mode} disabled={present.length === 1}
                  onClick={() => setPicked(picked === mode ? undefined : mode)}>
            <span className="legend-dot"/><ModeIcon mode={mode}/>{GAMEMODE_NAMES[mode]}
          </button>
        ))}
      </div>
      <div className="constellation-scroll">
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="group" aria-label="Nominators grouped by mode and connected by the beatmaps they ranked together" onPointerLeave={() => setActive(undefined)}>
          {data.links.filter(it => drawn.has(it) || own.includes(it)).map(link => {
            const a = points.get(link.a)!, b = points.get(link.b)!
            const highlighted = active ? link.a === active || link.b === active : !picked || !!link.modes[picked]
            return <line key={`${link.a}-${link.b}`} className={`star-link ${CLASSES[link.mode]}`} data-faded={!highlighted || undefined} data-active={(active || picked) && highlighted || undefined}
                         x1={a.x} y1={a.y} x2={b.x} y2={b.y} strokeWidth={1 + 5 * link.count / strongest}/>
          })}
          {data.people.map(it => {
            const {user, count} = it
            const point = points.get(user.osuId)!
            const size = radius(count, most)
            const parts = shares(it)
            const faded = active ? active !== user.osuId && !close.has(user.osuId) : picked && !it.modes[picked]
            let start = 0
            return (
              <a key={user.osuId} className="star" href={`/team?user=${user.osuId}`} data-faded={faded || undefined} data-active={active === user.osuId || undefined}
                 aria-label={`${user.username}, ${count} ranked in ${parts.map(part => GAMEMODE_NAMES[part.mode]).join(" and ")}`}
                 onPointerEnter={() => setActive(user.osuId)} onFocus={() => setActive(user.osuId)} onBlur={() => setActive(undefined)}
                 onClick={event => {
                   if (event.ctrlKey || event.metaKey) return
                   event.preventDefault()
                   navigate(`/team?user=${user.osuId}`)
                 }}>
                <circle className="star-hit" cx={point.x} cy={point.y} r={Math.max(size + 6, 16)}/>
                <circle className="star-ring" cx={point.x} cy={point.y} r={size + 2}/>
                {parts.map(({mode, part}) => {
                  const offset = start
                  start += part
                  return <circle key={mode} className={`star-part ${CLASSES[mode]}`} cx={point.x} cy={point.y} r={size / 2} strokeWidth={size} pathLength={100}
                                 strokeDasharray={parts.length === 1 ? undefined : `${part * 100} 100`} strokeDashoffset={-offset * 100}/>
                })}
                {(named.has(user.osuId) || close.has(user.osuId) || active === user.osuId || (picked && it.modes[picked])) &&
                  <text x={point.x} y={point.y + size + 18} textAnchor="middle">{user.username}</text>
                }
              </a>
            )
          })}
        </svg>
      </div>
      <p className="constellation-info" aria-live="polite">
        {person
          ? <>
              <strong>{person.user.username}</strong> {person.count} ranked ({shares(person).map(it => `${person.modes[it.mode]} ${GAMEMODE_NAMES[it.mode]}`).join(", ")})
              {own.length > 0 && <>, most with {own.slice(0, 3).map(it => `${nameOf(it.a === active ? it.b : it.a)} (${it.count})`).join(", ")}</>}
            </>
          : <span className="muted">
              Bigger is more ranked beatmaps, thicker is more together.
              {bridges > 0 && ` ${bridges} ${bridges === 1 ? "person connects" : "people connect"} several modes and ${bridges === 1 ? "sits" : "sit"} in between them.`}
              {" "}Point at someone to see who they nominate with, select them for their profile.{data.hidden > 0 && ` ${data.hidden} less active nominators are left out.`}
            </span>
        }
      </p>
      <Table head={["Pair", "Modes", "Ranked together"]} rows={data.links.slice(0, 40).map(it =>
        [`${nameOf(it.a)} and ${nameOf(it.b)}`, GAMEMODES.filter(mode => it.modes[mode]).map(mode => GAMEMODE_NAMES[mode]).join(", "), it.count])}/>
    </figure>
  )
}
