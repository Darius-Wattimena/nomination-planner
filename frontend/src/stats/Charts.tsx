import {useState} from "react";
import type {ReactNode} from "react";
import {GAMEMODE_NAMES} from "../Constants";
import type {Gamemode} from "../models/Types";
import {MODE_WORDS} from "../utils/query";
import {Table} from "./Columns";
import {scale, shownLabels} from "./stats";
import "./analytics.css";

export interface StackedRow {
  key: string
  label: ReactNode
  /** Said for the row in the table and to screen readers */
  name: string
  total: number
  parts: {mode: Gamemode, value: number}[]
  /** After the total, like a median */
  extra?: ReactNode
}

/** Horizontal bars split by mode, each mode in its own colour */
export function ModeBars({rows, label}: {rows: StackedRow[], label: string}) {
  const most = Math.max(1, ...rows.map(row => row.parts.reduce((sum, it) => sum + it.value, 0)))
  const modes = Array.from(new Set(rows.flatMap(row => row.parts.filter(it => it.value > 0).map(it => it.mode))))
  return (
    <figure className="chart">
      {modes.length > 1 && <Legend modes={modes}/>}
      <ul className="bars bars-wide an-funnel" aria-label={label}>
        {rows.map(row => (
          <li key={row.key} title={`${row.name}: ${row.total}`}>
            <span className="bars-name">{row.label}</span>
            <span className="bars-track an-stack" role="img" aria-label={`${row.name}: ${row.parts.filter(it => it.value > 0).map(it => `${it.value} ${GAMEMODE_NAMES[it.mode]}`).join(", ") || "none"}`}>
              {row.parts.filter(it => it.value > 0).map(it => <span key={it.mode} className={`in-${MODE_WORDS[it.mode]}`} style={{width: `${it.value / most * 100}%`}}/>)}
            </span>
            <span className="bars-value">{row.total}{row.extra}</span>
          </li>
        ))}
      </ul>
      <Table head={["", "Beatmaps", ...modes.map(it => GAMEMODE_NAMES[it])]} rows={rows.map(row => [row.name, row.total, ...modes.map(mode => row.parts.find(it => it.mode === mode)?.value ?? 0)])}/>
    </figure>
  )
}

export function Legend({modes}: {modes: Gamemode[]}) {
  return (
    <ul className="an-legend" aria-hidden>
      {modes.map(it => <li key={it} className={`in-${MODE_WORDS[it]}`}><span className="legend-dot"/>{GAMEMODE_NAMES[it]}</li>)}
    </ul>
  )
}

export interface SplitColumn {
  key: string
  short: string
  label: string
  /** Drawn at the bottom */
  first: number
  /** Drawn on top */
  second: number
  /** Still growing, drawn lighter */
  current?: boolean
}

/** Columns made of two parts, like new and returning */
export function SplitColumns({columns, names, label, height = 200}: {columns: SplitColumn[], names: [string, string], label: string, height?: number}) {
  const [active, setActive] = useState<number>()
  const max = Math.max(0, ...columns.map(it => it.first + it.second))
  const {top, ticks} = scale(max)
  const labels = shownLabels(columns.map(it => it.short))
  const shown = active !== undefined ? columns[active] : undefined
  const total = (it: SplitColumn) => it.first + it.second
  const highest = columns.find(it => total(it) === max)

  return (
    <figure className="chart">
      <ul className="an-legend" aria-hidden>
        <li><span className="legend-dot an-first"/>{names[0]}</li>
        <li><span className="legend-dot an-second"/>{names[1]}</li>
      </ul>
      <div className="columns" style={{height}} role="img" aria-label={`${label}. The highest is ${highest?.label ?? ""} with ${max}.`} onPointerLeave={() => setActive(undefined)}>
        <div className="columns-axis" aria-hidden>
          {ticks.map(tick => <span key={tick} style={{bottom: `${tick / top * 100}%`}}>{tick}</span>)}
        </div>
        <div className="columns-plot">
          {ticks.map(tick => <span key={tick} className="gridline" style={{bottom: `${tick / top * 100}%`}}/>)}
          {columns.map((column, index) => (
            <span key={column.key} className="column-hit" data-active={active === index || undefined}
                  onPointerEnter={() => setActive(index)} onPointerDown={() => setActive(index)}>
              <span className="column-bar an-split" data-unfinished={column.current || undefined} style={{height: `${total(column) / top * 100}%`}}>
                <span className="an-second" style={{flexGrow: column.second}}/>
                <span className="an-first" style={{flexGrow: column.first}}/>
              </span>
            </span>
          ))}
          {shown && active !== undefined &&
            <span className="tooltip" style={{left: `${(active + .5) / columns.length * 100}%`}} data-side={active < columns.length / 2 ? "right" : "left"}>
              <strong>{shown.label}{shown.current && ", so far"}</strong>
              <span>{shown.first} {names[0].toLowerCase()}</span>
              <span>{shown.second} {names[1].toLowerCase()}</span>
            </span>
          }
        </div>
      </div>
      <div className="columns-labels" aria-hidden>
        {columns.map((column, index) => <span key={column.key}>{labels[index] ? column.short : ""}</span>)}
      </div>
      <Table head={["", names[0], names[1]]} rows={columns.map(it => [it.label, it.first, it.second])}/>
    </figure>
  )
}

/** The share of the work (up) done by the least busy share of the people (right) */
export function Lorenz({points, label}: {points: {x: number, y: number}[], label: string}) {
  const size = 160
  const path = points.map((it, index) => `${index === 0 ? "M" : "L"}${(it.x * size).toFixed(1)},${(size - it.y * size).toFixed(1)}`).join(" ")
  const percent = (value: number) => `${Math.round(value * 100)}%`
  return (
    <figure className="chart an-lorenz">
      <svg viewBox={`-1 -1 ${size + 2} ${size + 2}`} role="img" aria-label={label}>
        <rect x={0} y={0} width={size} height={size} className="an-lorenz-frame"/>
        <line x1={0} y1={size} x2={size} y2={0} className="an-lorenz-equal"/>
        <path d={`${path} L${size},${size} Z`} className="an-lorenz-area"/>
        <path d={path} className="an-lorenz-line"/>
      </svg>
      <figcaption className="muted">Right: share of nominators, least busy first. Up: share of the nominations. The diagonal is everyone doing as much.</figcaption>
      <Table head={["Least busy nominators", "Their share of nominations"]} rows={points.map(it => [percent(it.x), percent(it.y)])}/>
    </figure>
  )
}
