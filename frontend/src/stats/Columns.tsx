import {useState} from "react";
import {Column, scale} from "./stats";

interface ColumnsProps {
  columns: Column[]
  /** What is counted, like "ranked beatmaps" */
  unit: string
  label: string
  /** The last column is still growing */
  unfinished?: boolean
  height?: number
}

export default function Columns({columns, unit, label, unfinished, height = 220}: ColumnsProps) {
  const [active, setActive] = useState<number>()
  const max = Math.max(...columns.map(it => it.value), 0)
  const {top, ticks} = scale(max)
  // Several years of months would repeat themselves, so only show where each year starts
  const years = columns.length > 14 && columns.some(it => /^[0-9]{4}$/.test(it.short))
  const highest = columns.findIndex(it => it.value === max)
  const shown = active !== undefined ? columns[active] : undefined

  return (
    <figure className="chart">
      <div className="columns" style={{height}} role="img" aria-label={`${label}. The highest is ${columns[highest]?.label ?? ""} with ${max} ${unit}.`} onPointerLeave={() => setActive(undefined)}>
        <div className="columns-axis" aria-hidden>
          {ticks.map(tick => <span key={tick} style={{bottom: `${tick / top * 100}%`}}>{tick}</span>)}
        </div>
        <div className="columns-plot">
          {ticks.map(tick => <span key={tick} className="gridline" style={{bottom: `${tick / top * 100}%`}}/>)}
          {columns.map((column, index) => (
            <span key={column.key} className="column-hit" data-active={active === index || undefined}
                  onPointerEnter={() => setActive(index)} onPointerDown={() => setActive(index)}>
              <span className="column-bar" data-unfinished={unfinished && index === columns.length - 1 || undefined} style={{height: `${column.value / top * 100}%`}}>
                {index === highest && max > 0 && <span className="column-value">{column.value}</span>}
              </span>
            </span>
          ))}
          {shown && active !== undefined &&
            <span className="tooltip" style={{left: `${(active + .5) / columns.length * 100}%`}} data-side={active < columns.length / 2 ? "right" : "left"}>
              <strong>{shown.value} {unit}</strong>
              <span>{shown.label}{unfinished && active === columns.length - 1 && ", so far"}</span>
            </span>
          }
        </div>
      </div>
      <div className="columns-labels" aria-hidden>
        {columns.map(column => <span key={column.key}>{!years || /^[0-9]{4}$/.test(column.short) ? column.short : ""}</span>)}
      </div>
      <Table head={["", unit]} rows={columns.map(it => [it.label, it.value])}/>
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
