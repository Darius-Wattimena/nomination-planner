import {useId, useMemo, useState} from "react";
import {FaMagnifyingGlass} from "react-icons/fa6";
import type {Pairing} from "../models/Types";
import {useRanked} from "../resources/ranked";
import {displayName} from "../utils/users";
import {Table} from "./Columns";
import {insights, knownNominators, monthly} from "./insights";
import {scale} from "./stats";
import "./insights.css";

interface InsightsProps {
  id: string
  self: boolean
  /** Their name, to talk about them on the profile of somebody else */
  name: string
}

/** A few plain sentences about the last 12 months */
export default function Insights({id, self, name}: InsightsProps) {
  const {maps} = useRanked()
  const cards = useMemo(() => maps ? insights(maps, id, {self, name}) : [], [maps, id, self, name])
  if (cards.length === 0) return null

  return (
    <section className="map-group">
      <header><h2>Insights</h2><span className="muted">The last 12 months</span></header>
      <ul className="insights">
        {cards.map(it => (
          <li key={it.key} className="insight">
            <span className="insight-title">{it.title}</span>
            <strong className="insight-figure">{it.figure}</strong>
            <p>{it.text}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}

interface CompareProps {
  id: string
  name: string
  /** Who they nominate with, offered first */
  partners: Pairing[]
}

/** Somebody else next to them, month by month */
export function Compare({id, name, partners}: CompareProps) {
  const {maps} = useRanked()
  const [other, setOther] = useState<string>()
  const [text, setText] = useState("")
  const list = useId()
  const known = useMemo(() => knownNominators(maps ?? []).filter(it => it.osuId !== id), [maps, id])
  const offered = partners.filter(it => it.osuId !== id && it.pairingRanked > 0).slice(0, 8)
  const own = useMemo(() => monthly(maps ?? [], id), [maps, id])
  const theirs = useMemo(() => other ? monthly(maps ?? [], other) : undefined, [maps, other])
  if (!maps || known.length === 0) return null

  const otherName = other ? (known.find(it => it.osuId === other) ? displayName(known.find(it => it.osuId === other)!) : offered.find(it => it.osuId === other)?.name ?? `User ${other}`) : ""

  function search(value: string) {
    setText(value)
    const found = known.find(it => displayName(it).toLowerCase() === value.trim().toLowerCase())
    if (found) setOther(found.osuId)
  }

  return (
    <details className="map-group compare">
      <summary><h2>Compare</h2><span className="muted">With somebody else, month by month</span></summary>
      <div className="compare-pick">
        {offered.length > 0 &&
          <label className="muted">Partner{" "}
            <select value={offered.some(it => it.osuId === other) ? other : ""} onChange={event => { setOther(event.target.value || undefined); setText("") }}>
              <option value="">Choose…</option>
              {offered.map(it => <option key={it.osuId} value={it.osuId}>{it.name}</option>)}
            </select>
          </label>
        }
        <label className="search">
          <FaMagnifyingGlass aria-hidden/>
          <input type="search" list={list} aria-label="Find a nominator to compare with" placeholder="Find a nominator" value={text} onChange={event => search(event.target.value)}/>
        </label>
        <datalist id={list}>{known.map(it => <option key={it.osuId} value={displayName(it)}/>)}</datalist>
      </div>
      {theirs && <Paired label={`Ranked beatmaps per month in the last 12 months, ${name} and ${otherName}`} names={[name, otherName]} series={[own, theirs]}/>}
    </details>
  )
}

/** Two columns per month, looking like the other charts */
function Paired({label, names, series}: {label: string, names: [string, string], series: [ReturnType<typeof monthly>, ReturnType<typeof monthly>]}) {
  const [active, setActive] = useState<number>()
  const [a, b] = series
  const max = Math.max(0, ...a.map(it => it.value), ...b.map(it => it.value))
  const {top, ticks} = scale(max)
  const total = (columns: typeof a) => columns.reduce((sum, it) => sum + it.value, 0)

  return (
    <figure className="chart compare-chart">
      <p className="legend muted"><span className="key key-check"/>{names[0]}, {total(a)} <span className="key key-wait"/>{names[1]}, {total(b)}</p>
      <div className="columns" style={{height: 170}} role="img" aria-label={`${label}. ${names[0]} ranked ${total(a)}, ${names[1]} ranked ${total(b)}.`} onPointerLeave={() => setActive(undefined)}>
        <div className="columns-axis" aria-hidden>
          {ticks.map(tick => <span key={tick} style={{bottom: `${tick / top * 100}%`}}>{tick}</span>)}
        </div>
        <div className="columns-plot">
          {ticks.map(tick => <span key={tick} className="gridline" style={{bottom: `${tick / top * 100}%`}}/>)}
          {a.map((column, index) => (
            <span key={column.key} className="column-hit compare-pair" data-active={active === index || undefined}
                  onPointerEnter={() => setActive(index)} onPointerDown={() => setActive(index)}>
              <span className="column-bar" data-unfinished={index === a.length - 1 || undefined} style={{height: `${column.value / top * 100}%`}}/>
              <span className="column-bar compare-other" data-unfinished={index === a.length - 1 || undefined} style={{height: `${b[index].value / top * 100}%`}}/>
            </span>
          ))}
          {active !== undefined &&
            <span className="tooltip" style={{left: `${(active + .5) / a.length * 100}%`}} data-side={active < a.length / 2 ? "right" : "left"}>
              <strong>{a[active].label}{active === a.length - 1 && ", so far"}</strong>
              <span>{names[0]}: {a[active].value}</span>
              <span>{names[1]}: {b[active].value}</span>
            </span>
          }
        </div>
      </div>
      <div className="columns-labels" aria-hidden>
        {a.map(column => <span key={column.key}>{column.label.slice(0, 3)}</span>)}
      </div>
      <Table head={["", names[0], names[1]]} rows={a.map((it, index) => [it.label, it.value, b[index].value])}/>
    </figure>
  )
}
