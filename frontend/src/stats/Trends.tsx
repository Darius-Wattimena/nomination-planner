import {useState} from "react";
import type {ReactNode} from "react";
import {GAMEMODE_NAMES} from "../Constants";
import {MODE_WORDS} from "../utils/query";
import {Legend} from "./Charts";
import {Table} from "./Columns";
import type {ShareColumn} from "./community";
import {scale, shownLabels} from "./stats";
import "./analytics.css";
import "./communitySpeed.css";

/** A bucket along the bottom of a chart */
export interface Slot {
  key: string
  short: string
  label: string
  current?: boolean
}

const dash = "–"
const percent = (value: number | undefined) => value === undefined ? dash : `${Math.round(value * 100)}%`

/** The frame every chart here shares: the axis, the grid lines, a hover area per bucket, a tooltip and the labels */
function Frame({slots, ticks, top, format, height, label, children, tip, overlay}: {
  slots: Slot[]
  ticks: number[]
  top: number
  format: (value: number) => string
  height: number
  label: string
  /** Drawn inside each bucket */
  children: (index: number) => ReactNode
  tip: (index: number) => ReactNode
  /** Over the whole plot, like lines */
  overlay?: ReactNode
}) {
  const [active, setActive] = useState<number>()
  const labels = shownLabels(slots.map(it => it.short))
  return (
    <>
      <div className="columns" style={{height}} role="img" aria-label={label} onPointerLeave={() => setActive(undefined)}>
        <div className="columns-axis" aria-hidden>
          {ticks.map(tick => <span key={tick} style={{bottom: `${tick / top * 100}%`}}>{format(tick)}</span>)}
        </div>
        <div className="columns-plot">
          {ticks.map(tick => <span key={tick} className="gridline" style={{bottom: `${tick / top * 100}%`}}/>)}
          {slots.map((slot, index) => (
            <span key={slot.key} className="column-hit" data-active={active === index || undefined}
                  onPointerEnter={() => setActive(index)} onPointerDown={() => setActive(index)}>
              {children(index)}
            </span>
          ))}
          {overlay}
          {active !== undefined && slots[active] &&
            <span className="tooltip" style={{left: `${(active + .5) / slots.length * 100}%`}} data-side={active < slots.length / 2 ? "right" : "left"}>
              <strong>{slots[active].label}{slots[active].current && ", so far"}</strong>
              {tip(active)}
            </span>
          }
        </div>
      </div>
      <div className="columns-labels" aria-hidden>
        {slots.map((slot, index) => <span key={slot.key}>{labels[index] ? slot.short : ""}</span>)}
      </div>
    </>
  )
}

export interface BandPoint extends Slot {
  count: number
  low?: number
  middle?: number
  high?: number
}

/** The middle per bucket as a thick mark, with the range around it as a lighter bar behind. A bucket without a middle is a gap. */
export function BandChart({points, label, names, unit, height = 220}: {
  points: BandPoint[]
  label: string
  /** For the low, middle and high values, like ["25th percentile", "Median", "75th percentile"] */
  names: [string, string, string]
  /** After a value, like "days" */
  unit: string
  height?: number
}) {
  const {top, ticks} = scale(Math.max(0, ...points.map(it => it.high ?? 0)))
  const shown = points.filter(it => it.middle !== undefined)
  const at = (value: number) => `${value / top * 100}%`
  return (
    <figure className="chart">
      <ul className="an-legend" aria-hidden>
        <li><span className="tr-key-middle"/>{names[1]}</li>
        <li><span className="tr-key-band"/>{names[0].replace(" percentile", "")} to {names[2]}</li>
      </ul>
      <Frame slots={points} ticks={ticks} top={top} format={String} height={height} label={`${label}. ${shown.length} of ${points.length} have enough beatmaps to show.`}
             tip={index => {
               const it = points[index]
               return it.middle === undefined
                 ? <span>{it.count === 0 ? "Nothing ranked" : `Only ${it.count}, too few to show`}</span>
                 : <>
                   <span>{names[1]}: {it.middle} {unit}</span>
                   <span>{it.low} to {it.high} {unit}</span>
                   <span>{it.count} beatmaps</span>
                 </>
             }}>
        {index => {
          const it = points[index]
          if (it.middle === undefined || it.low === undefined || it.high === undefined) return null
          return (
            <span className="tr-slot" data-unfinished={it.current || undefined}>
              <span className="tr-band" style={{bottom: at(it.low), height: `max(2px, ${(it.high - it.low) / top * 100}%)`}}/>
              <span className="tr-middle" style={{bottom: at(it.middle)}}/>
            </span>
          )
        }}
      </Frame>
      <Table head={["", names[0], names[1], names[2], "Beatmaps"]}
             rows={points.map(it => [it.label, it.low ?? dash, it.middle ?? dash, it.high ?? dash, it.count])}/>
    </figure>
  )
}

export interface LineSeries {
  name: string
  /** A value or a gap for every bucket */
  values: (number | undefined)[]
}

/** One or two lines with a dot on every value, broken where there is a gap. The second line is dashed with square dots. */
export function LineChart({slots, series, label, height = 180, share, unit = "", counts, table = true}: {
  slots: Slot[]
  series: LineSeries[]
  label: string
  height?: number
  /** The values are shares from 0 to 1, drawn as percentages up to 100% */
  share?: boolean
  /** After a value that isn't a share, like "days" */
  unit?: string
  /** How many beatmaps every bucket has, said in the tooltip and the table */
  counts?: number[]
  table?: boolean
}) {
  const highest = Math.max(0, ...series.flatMap(it => it.values.map(value => value ?? 0)))
  const {top, ticks} = share ? {top: 1, ticks: [0, .25, .5, .75, 1]} : scale(highest)
  const format = (value: number | undefined) => value === undefined ? dash : share ? percent(value) : `${Math.round(value * 10) / 10}`
  const path = (values: (number | undefined)[]) => values.map((value, index) =>
    value === undefined ? "" : `${index === 0 || values[index - 1] === undefined ? "M" : "L"}${index + .5},${top - value}`).join(" ")

  return (
    <figure className="chart">
      {series.length > 1 &&
        <ul className="an-legend" aria-hidden>
          {series.map((it, index) => <li key={it.name}><span className="tr-key-line" data-series={index}/>{it.name}</li>)}
        </ul>
      }
      <Frame slots={slots} ticks={ticks} top={top} format={value => share ? percent(value) : String(value)} height={height} label={label}
               tip={index => <>
                 {series.map(it => <span key={it.name}>{series.length > 1 && `${it.name}: `}{format(it.values[index])}{!share && it.values[index] !== undefined && ` ${unit}`}</span>)}
                 {counts && <span>{counts[index]} beatmaps</span>}
               </>}
             overlay={
               <svg className="columns-line tr-paths" viewBox={`0 0 ${Math.max(1, slots.length)} ${top}`} preserveAspectRatio="none" aria-hidden>
                 {series.map((it, which) => <path key={it.name} d={path(it.values)} data-series={which} vectorEffect="non-scaling-stroke"/>)}
               </svg>
             }>
          {index => series.map((it, which) => {
            const value = it.values[index]
            return value === undefined ? null :
              <span key={it.name} className="tr-dot" data-series={which} data-unfinished={slots[index].current || undefined} style={{bottom: `${value / top * 100}%`}}/>
          })}
      </Frame>
      {table &&
        <Table head={["", ...series.map(it => it.name), ...(counts ? ["Beatmaps"] : [])]}
               rows={slots.map((slot, index) => [slot.label, ...series.map(it => format(it.values[index])), ...(counts ? [counts[index]] : [])])}/>
      }
    </figure>
  )
}

/** Columns that are always full, split into the share of each mode. A bucket without anything is a gap. */
export function ShareColumns({columns, label, height = 200}: {columns: ShareColumn[], label: string, height?: number}) {
  const modes = columns[0]?.parts.map(it => it.mode).filter(mode => columns.some(column => column.parts.some(it => it.mode === mode && it.value > 0))) ?? []
  const share = (column: ShareColumn, value: number) => column.value > 0 ? value / column.value : undefined
  return (
    <figure className="chart">
      {modes.length > 0 && <Legend modes={modes}/>}
      <Frame slots={columns} ticks={[0, .5, 1]} top={1} format={percent} height={height} label={label}
             tip={index => {
               const column = columns[index]
               return column.value === 0 ? <span>Nothing ranked</span> : <>
                 {column.parts.filter(it => it.value > 0).map(it => <span key={it.mode}>{GAMEMODE_NAMES[it.mode]}: {percent(share(column, it.value))} ({it.value})</span>)}
               </>
             }}>
        {index => {
          const column = columns[index]
          if (column.value === 0) return null
          return (
            <span className="column-bar tr-stack" data-unfinished={column.current || undefined}>
              {column.parts.filter(it => it.value > 0).map(it => <span key={it.mode} className={`in-${MODE_WORDS[it.mode]}`} style={{flexGrow: it.value}}/>)}
            </span>
          )
        }}
      </Frame>
      <Table head={["", "Nominations", ...modes.map(it => GAMEMODE_NAMES[it])]}
             rows={columns.map(column => [column.label, column.value, ...modes.map(mode => column.value === 0 ? dash : percent(share(column, column.parts.find(it => it.mode === mode)?.value ?? 0)))])}/>
    </figure>
  )
}
