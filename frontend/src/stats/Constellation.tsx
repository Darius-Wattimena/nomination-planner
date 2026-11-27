import type {PointerEvent as ReactPointerEvent} from "react";
import {useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import {Link as RouterLink} from "react-router";
import {FaExpand, FaMagnifyingGlass, FaMinus, FaPlus, FaXmark} from "react-icons/fa6";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import {Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {Avatar} from "../ui/Person";
import {Table} from "./Columns";
import type {Camera, Network, Point, Size, Star} from "./sky";
import {findStars, fit, isVisible, layout, pan, partnersOf, placeNames, radius, shares, starScale, strongestLinks, toScreen, zoomAt} from "./sky";
import type {Link} from "./stats";

const CLASSES: Record<Gamemode, string> = {
  [Gamemode.Osu]: "in-osu",
  [Gamemode.Taiko]: "in-taiko",
  [Gamemode.Catch]: "in-catch",
  [Gamemode.Mania]: "in-mania"
}

/** Moving less than this is still a tap */
const DRAG = 6
/** Names of partners that are always shown */
const NAMED_PARTNERS = 8

type Selection = {person: string} | {pair: [string, string]}

const modeNames = (modes: Partial<Record<Gamemode, number>>) => GAMEMODES.filter(mode => modes[mode]).map(mode => GAMEMODE_NAMES[mode]).join(", ")

/** Everybody is a star in the colors of their modes, the lines are the beatmaps they ranked together */
export default function Constellation({data}: {data: Network}) {
  const frame = useRef<HTMLDivElement>(null)
  const svg = useRef<SVGSVGElement>(null)
  const [size, setSize] = useState<Size>()
  const [camera, setCamera] = useState<Camera>()
  const [hovered, setHovered] = useState<string>()
  const [selection, setSelection] = useState<Selection>()
  const [picked, setPicked] = useState<Gamemode>()
  const [search, setSearch] = useState("")

  const points = useMemo(() => layout(data), [data])
  const byId = useMemo(() => new Map(data.people.map(it => [it.user.osuId, it])), [data])
  const most = Math.max(1, ...data.people.map(it => it.count))
  const strongest = Math.max(1, ...data.links.map(it => it.count))
  const nameOf = (id: string) => byId.get(id)?.user.username ?? ""

  // The sky takes the room it gets, which differs between a phone and a desktop
  useLayoutEffect(() => {
    const element = frame.current
    if (!element) return
    const measure = () => setSize(it => {
      const next = {width: element.clientWidth, height: element.clientHeight}
      return it && it.width === next.width && it.height === next.height ? it : next
    })
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // Start with everyone in view
  if (size && size.width > 0 && !camera && points.size > 0) setCamera(fit(Array.from(points.values()), size))

  function focusOn(ids: string[]) {
    if (!size) return
    const known = ids.map(it => points.get(it)).filter((it): it is Point => !!it)
    if (known.length === 0) return
    const target = fit(known, size)
    setCamera(known.length === 1 ? {...target, zoom: Math.max(camera?.zoom ?? 1, 1)} : target)
  }

  function choose(id: string) {
    setSelection({person: id})
    setSearch("")
    focusOn([id, ...partnersOf(data.links, id).slice(0, 5).map(it => it.id)])
  }

  function choosePair(a: string, b: string) {
    setSelection({pair: [a, b]})
    focusOn([a, b])
  }

  // Zooming with the mouse wheel, which needs a listener that may stop the page from scrolling
  useEffect(() => {
    const element = svg.current
    if (!element || !size) return
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      const bounds = element.getBoundingClientRect()
      const delta = event.deltaY * (event.deltaMode === 1 ? 16 : 1)
      setCamera(it => it && zoomAt(it, size, Math.exp(-delta * .0015), {x: event.clientX - bounds.left, y: event.clientY - bounds.top}))
    }
    element.addEventListener("wheel", wheel, {passive: false})
    return () => element.removeEventListener("wheel", wheel)
  }, [size])

  // Dragging with a mouse or a finger, pinching with two fingers
  const pointers = useRef(new Map<number, Point>())
  const gesture = useRef({start: {x: 0, y: 0}, moved: false})

  function position(event: ReactPointerEvent): Point {
    const bounds = svg.current!.getBoundingClientRect()
    return {x: event.clientX - bounds.left, y: event.clientY - bounds.top}
  }

  function down(event: ReactPointerEvent) {
    if (event.button !== 0) return
    const at = position(event)
    pointers.current.set(event.pointerId, at)
    if (pointers.current.size === 1) gesture.current = {start: at, moved: false}
  }

  function move(event: ReactPointerEvent) {
    const before = pointers.current.get(event.pointerId)
    if (!before || !size) return
    const at = position(event)
    pointers.current.set(event.pointerId, at)
    const other = Array.from(pointers.current.entries()).find(([id]) => id !== event.pointerId)?.[1]

    if (other) {
      const distanceBefore = Math.max(1, Math.hypot(before.x - other.x, before.y - other.y))
      const distance = Math.max(1, Math.hypot(at.x - other.x, at.y - other.y))
      const middleBefore = {x: (before.x + other.x) / 2, y: (before.y + other.y) / 2}
      const middle = {x: (at.x + other.x) / 2, y: (at.y + other.y) / 2}
      gesture.current.moved = true
      setCamera(it => it && pan(zoomAt(it, size, distance / distanceBefore, middle), middle.x - middleBefore.x, middle.y - middleBefore.y))
      return
    }
    const {start} = gesture.current
    if (!gesture.current.moved && Math.hypot(at.x - start.x, at.y - start.y) < DRAG) return
    if (!gesture.current.moved) {
      gesture.current.moved = true
      svg.current!.setPointerCapture(event.pointerId)
    }
    setCamera(it => it && pan(it, at.x - before.x, at.y - before.y))
  }

  function up(event: ReactPointerEvent) {
    pointers.current.delete(event.pointerId)
  }

  // Who is looked at: the person pointed at, otherwise who was selected
  const selected = selection && "person" in selection ? selection.person : undefined
  const pair = selection && "pair" in selection ? selection.pair : undefined
  const focus = hovered ?? selected
  const partners = useMemo(() => focus ? partnersOf(data.links, focus) : [], [data, focus])
  const close = new Set(focus ? [focus, ...partners.map(it => it.id)] : pair ?? [])
  const lit = (id: string) => close.size > 0 ? close.has(id) : !picked || !!byId.get(id)?.modes[picked]
  const pairLink = pair && data.links.find(it => (it.a === pair[0] && it.b === pair[1]) || (it.a === pair[1] && it.b === pair[0]))

  const scale = camera ? starScale(camera.zoom) : 1
  const screen = new Map(camera && size ? data.people.map(it => [it.user.osuId, toScreen(camera, size, points.get(it.user.osuId)!)]) : [])
  const sizes = new Map(data.people.map(it => [it.user.osuId, radius(it.count, most) * scale]))
  const visible = size ? data.people.filter(it => screen.has(it.user.osuId) && isVisible(screen.get(it.user.osuId)!, size)) : []

  const drawn = new Set([...strongestLinks(data.links, data.people.length), ...partners.map(it => it.link), ...(pairLink ? [pairLink] : [])])
  const lineLit = (link: Link) => focus ? link.a === focus || link.b === focus : pair ? link === pairLink : !picked || !!link.modes[picked]
  const lines = size ? Array.from(drawn).filter(link => {
    const a = screen.get(link.a), b = screen.get(link.b)
    return !!a && !!b && Math.max(a.x, b.x) > 0 && Math.min(a.x, b.x) < size.width && Math.max(a.y, b.y) > 0 && Math.min(a.y, b.y) < size.height
  }).sort((a, b) => Number(lineLit(a)) - Number(lineLit(b))) : []

  // Names that fit, those that matter now first
  const always = new Set([...(focus ? [focus, ...partners.slice(0, NAMED_PARTNERS).map(it => it.id)] : []), ...(pair ?? [])])
  const order = picked ? [...visible.filter(it => it.modes[picked]), ...visible.filter(it => !it.modes[picked])] : visible
  const named = size ? placeNames(order, screen, sizes, size, always) : new Set<string>()

  const present = GAMEMODES.filter(mode => data.people.some(it => it.modes[mode]))
  const bridges = data.people.filter(it => shares(it).length > 1).length
  const found = findStars(data.people, search)
  const person = selected ? byId.get(selected) : undefined
  const selectedPartners = selected ? partnersOf(data.links, selected) : []
  const mostTogether = Math.max(1, ...selectedPartners.map(it => it.link.count))
  const searching = search.trim() !== ""

  const star = (it: Star) => {
    const {user, count} = it
    const point = screen.get(user.osuId)!
    const r = sizes.get(user.osuId)!
    const parts = shares(it)
    let start = 0
    return (
      <a key={user.osuId} className="star" href={`/team?user=${user.osuId}`} data-faded={!lit(user.osuId) || undefined}
         data-active={focus === user.osuId || pair?.includes(user.osuId) || undefined}
         aria-label={`${user.username}, ${count} ranked in ${parts.map(part => GAMEMODE_NAMES[part.mode]).join(" and ")}`}
         onPointerEnter={event => event.pointerType === "mouse" && setHovered(user.osuId)}
         onPointerLeave={() => setHovered(undefined)}
         onFocus={() => setHovered(user.osuId)}
         onBlur={() => setHovered(undefined)}
         onClick={event => {
           // Opening the profile in another tab still works
           if (event.ctrlKey || event.metaKey) return
           event.preventDefault()
           if (!gesture.current.moved) choose(user.osuId)
         }}>
        <circle className="star-hit" cx={point.x} cy={point.y} r={Math.max(r + 6, 14)}/>
        <circle className="star-ring" cx={point.x} cy={point.y} r={r + 2}/>
        {parts.map(({mode, part}) => {
          const offset = start
          start += part
          return <circle key={mode} className={`star-part ${CLASSES[mode]}`} cx={point.x} cy={point.y} r={r / 2} strokeWidth={r} pathLength={100}
                         strokeDasharray={parts.length === 1 ? undefined : `${part * 100} 100`} strokeDashoffset={-offset * 100}/>
        })}
        {named.has(user.osuId) && <text x={point.x} y={point.y + r + 15} textAnchor="middle">{user.username}</text>}
      </a>
    )
  }

  const zoomButton = (factor: number) => size && setCamera(it => it && zoomAt(it, size, factor, {x: size.width / 2, y: size.height / 2}))

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
      <div className="sky">
        <div className="sky-frame" ref={frame}>
          <svg ref={svg} width={size?.width} height={size?.height} role="group" aria-label="Nominators grouped by mode and connected by the beatmaps they ranked together"
               onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
            <rect className="sky-background" width="100%" height="100%" onClick={() => !gesture.current.moved && setSelection(undefined)}/>
            {lines.map(link => {
              const a = screen.get(link.a)!, b = screen.get(link.b)!
              const highlighted = lineLit(link)
              return <line key={`${link.a}-${link.b}`} className={`star-link ${CLASSES[link.mode]}`} data-faded={!highlighted || undefined}
                           data-active={(focus || pair || picked) && highlighted || undefined}
                           x1={a.x} y1={a.y} x2={b.x} y2={b.y} strokeWidth={(1 + 5 * link.count / strongest) * scale}/>
            })}
            {visible.filter(it => !lit(it.user.osuId)).map(star)}
            {visible.filter(it => lit(it.user.osuId) && !always.has(it.user.osuId)).map(star)}
            {visible.filter(it => always.has(it.user.osuId)).map(star)}
          </svg>
          <div className="sky-controls" role="group" aria-label="Zoom">
            <button className="icon-button" aria-label="Zoom in" title="Zoom in" onClick={() => zoomButton(1.5)}><FaPlus/></button>
            <button className="icon-button" aria-label="Zoom out" title="Zoom out" onClick={() => zoomButton(1 / 1.5)}><FaMinus/></button>
            <button className="icon-button" aria-label="Show everyone" title="Show everyone" onClick={() => size && setCamera(fit(Array.from(points.values()), size))}><FaExpand/></button>
          </div>
        </div>

        <aside className="sky-panel">
          <label className="search">
            <FaMagnifyingGlass aria-hidden/>
            <input id="constellation-search" type="search" aria-label="Find a nominator" placeholder="Find a nominator" value={search} onChange={event => setSearch(event.target.value)}/>
          </label>

          {searching &&
            <ul className="sky-list" aria-label="Found">
              {found.length === 0 && <li className="muted">Nobody with that name ranked something here.</li>}
              {found.map(it => (
                <li key={it.user.osuId}>
                  <button onClick={() => choose(it.user.osuId)}><span>{it.user.username}</span><span className="load-text">{it.count} ranked</span></button>
                </li>
              ))}
            </ul>
          }

          {!searching && person &&
            <section aria-label={person.user.username}>
              <header className="sky-person">
                <Avatar user={person.user}/>
                <div>
                  <RouterLink to={`/team?user=${person.user.osuId}`}>{person.user.username}</RouterLink>
                  <span className="muted">{person.count} ranked, {shares(person).map(it => `${person.modes[it.mode]} ${GAMEMODE_NAMES[it.mode]}`).join(", ")}</span>
                </div>
                <button className="icon-button" aria-label="Show everyone again" title="Show everyone again" onClick={() => setSelection(undefined)}><FaXmark/></button>
              </header>
              <h3>Ranked together with {selectedPartners.length}</h3>
              {selectedPartners.length === 0 && <p className="muted">Nobody else in this selection.</p>}
              <ul className="sky-list">
                {selectedPartners.map(({id, link}) => (
                  <li key={id}>
                    <button onClick={() => choose(id)}>
                      <span>{nameOf(id)}</span><span className="load-text">{link.count}</span>
                      <span className="load" aria-hidden><span className="load-check" style={{width: `${link.count / mostTogether * 100}%`}}/></span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          }

          {!searching && pair &&
            <section aria-label="Pair">
              <header className="sky-person">
                <div>
                  <strong>{nameOf(pair[0])} and {nameOf(pair[1])}</strong>
                  {pairLink && <span className="muted">{pairLink.count} ranked together, {modeNames(pairLink.modes)}</span>}
                </div>
                <button className="icon-button" aria-label="Show everyone again" title="Show everyone again" onClick={() => setSelection(undefined)}><FaXmark/></button>
              </header>
              <ul className="sky-list">
                {pair.map(id => <li key={id}><button onClick={() => choose(id)}><span>Everyone {nameOf(id)} ranked with</span></button></li>)}
              </ul>
            </section>
          }

          {!searching && !selection &&
            <section aria-label="Strongest pairs">
              <h3>Strongest pairs</h3>
              {data.links.length === 0 && <p className="muted">Nobody ranked together here.</p>}
              <ol className="sky-list">
                {data.links.slice(0, 10).map(it => (
                  <li key={`${it.a}-${it.b}`}>
                    <button onClick={() => choosePair(it.a, it.b)}><span>{nameOf(it.a)} and {nameOf(it.b)}</span><span className="load-text">{it.count}</span></button>
                  </li>
                ))}
              </ol>
            </section>
          }
        </aside>
      </div>
      <p className="constellation-info muted">
        Scroll or pinch to zoom, drag to look around. Bigger is more ranked beatmaps, thicker is more together.
        {bridges > 0 && ` ${bridges} ${bridges === 1 ? "person connects" : "people connect"} several modes and ${bridges === 1 ? "sits" : "sit"} in between them.`}
        {" "}Select someone to see who they ranked with.
      </p>
      <Table head={["Pair", "Modes", "Ranked together"]} rows={data.links.slice(0, 40).map(it =>
        [`${nameOf(it.a)} and ${nameOf(it.b)}`, modeNames(it.modes), it.count])}/>
    </figure>
  )
}
