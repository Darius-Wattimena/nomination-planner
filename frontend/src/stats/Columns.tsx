import {useState} from "react";
import type {Column} from "./stats";
import {scale, shownLabels} from "./stats";

interface ColumnsProps {
  columns: Column[]
  /** What is counted, like "ranked beatmaps" */
  unit: string
  label: string
  /** The last column is still growing, also when the column itself doesn't say so */
  unfinished?: boolean
  height?: number
  /** A line over the columns, like a 7-day average, with a value or a gap for every column */
  line?: (number | undefined)[]
  lineName?: string
}

export default function Columns({columns, unit, label, unfinished, height = 220, line, lineName = "7-day average"}: ColumnsProps) {
  const [active, setActive] = useState<number>()
  const max = Math.max(...columns.map(it => it.value), 0)
  const {top, ticks} = scale(Math.max(max, ...(line ?? []).map(it => it ?? 0)))
  const labels = shownLabels(columns.map(it => it.short))
  const highest = columns.findIndex(it => it.value === max)
  const shown = active !== undefined ? columns[active] : undefined
  const growing = (index: number) => columns[index].current ?? (unfinished && index === columns.length - 1)
  const round = (value: number | undefined) => value === undefined ? "–" : Math.round(value * 10) / 10
  // Breaks where there is no value
  const path = line?.map((value, index) => value === undefined ? "" : `${index === 0 || line[index - 1] === undefined ? "M" : "L"}${index + .5},${top - value}`).join(" ")

  return (
    <figure className="chart">
      {path && <p className="columns-key muted"><span className="columns-key-line"/>{lineName}</p>}
      <div className="columns" style={{height}} role="img" aria-label={`${label}. The highest is ${columns[highest]?.label ?? ""} with ${max} ${unit}.`} onPointerLeave={() => setActive(undefined)}>
        <div className="columns-axis" aria-hidden>
          {ticks.map(tick => <span key={tick} style={{bottom: `${tick / top * 100}%`}}>{tick}</span>)}
        </div>
        <div className="columns-plot">
          {ticks.map(tick => <span key={tick} className="gridline" style={{bottom: `${tick / top * 100}%`}}/>)}
          {columns.map((column, index) => (
            <span key={column.key} className="column-hit" data-active={active === index || undefined}
                  onPointerEnter={() => setActive(index)} onPointerDown={() => setActive(index)}>
              <span className="column-bar" data-unfinished={growing(index) || undefined} style={{height: `${column.value / top * 100}%`}}>
                {index === highest && max > 0 && <span className="column-value">{column.value}</span>}
              </span>
            </span>
          ))}
          {path &&
            <svg className="columns-line" viewBox={`0 0 ${columns.length} ${top}`} preserveAspectRatio="none" aria-hidden>
              <path d={path} vectorEffect="non-scaling-stroke"/>
            </svg>
          }
          {shown && active !== undefined &&
            <span className="tooltip" style={{left: `${(active + .5) / columns.length * 100}%`}} data-side={active < columns.length / 2 ? "right" : "left"}>
              <strong>{shown.value} {unit}</strong>
              <span>{shown.label}{growing(active) && ", so far"}</span>
              {line && <span>{lineName}: {round(line[active])}</span>}
            </span>
          }
        </div>
      </div>
      <div className="columns-labels" aria-hidden>
        {columns.map((column, index) => <span key={column.key}>{labels[index] ? column.short : ""}</span>)}
      </div>
      <Table head={line ? ["", unit, lineName] : ["", unit]} rows={columns.map((it, index) => line ? [it.label, it.value, round(line[index])] : [it.label, it.value])}/>
    </figure>
  )
}

export function Table({head, rows}: {head: string[], rows: (string | number)[][]}) {
  return (
    <details className="chart-table">
      <summary>Show as table</summary>
      <div>
        <table>
          <thead><tr>{head.map((it, index) => <th key={index} scope="col">{it}</th>)}</tr></thead>
          <tbody>{rows.map((row, index) => <tr key={index}>{row.map((it, cell) => cell === 0 ? <th key={cell} scope="row">{it}</th> : <td key={cell}>{it}</td>)}</tr>)}</tbody>
        </table>
      </div>
    </details>
  )
}
