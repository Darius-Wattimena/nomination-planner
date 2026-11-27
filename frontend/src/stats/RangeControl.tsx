import type {TimeRange, Unit} from "./range";
import {customRange, dayString, PRESETS, UNITS, unitsFor} from "./range";

interface RangeControlProps {
  range: TimeRange
  /** Years with something ranked, the latest first */
  years: number[]
  unit: Unit
  onRange: (key: string) => void
  onUnit: (unit: Unit) => void
}

const UNIT_NAMES: Record<Unit, string> = {day: "Day", week: "Week", month: "Month", year: "Year"}

/** Which period to count in, and per what */
export default function RangeControl({range, years, unit, onRange, onUnit}: RangeControlProps) {
  const isCustom = range.key.includes("..")
  const year = /^\d{4}$/.test(range.key) ? Number(range.key) : undefined
  const allowed = unitsFor(range)
  const first = dayString(range.from)
  const last = dayString(new Date(range.to.getTime() - 1))

  function pick(value: string) {
    onRange(value === "custom" ? customRange(first, last) : value)
  }

  function change(from: string, to: string) {
    if (from && to && from <= to) onRange(customRange(from, to))
  }

  return (
    <div className="range-control">
      <select aria-label="Period" value={isCustom ? "custom" : range.key} onChange={event => pick(event.target.value)}>
        {PRESETS.map(it => <option key={it.key} value={it.key}>{it.label}</option>)}
        <optgroup label="Year">
          {(year && !years.includes(year) ? [year, ...years] : years).map(it => <option key={it} value={it}>{it}</option>)}
        </optgroup>
        <option value="custom">Custom…</option>
      </select>
      {isCustom &&
        <span className="range-custom">
          <input type="date" aria-label="From" value={first} max={last} onChange={event => change(event.target.value, last)}/>
          <input type="date" aria-label="Until" value={last} min={first} onChange={event => change(first, event.target.value)}/>
        </span>
      }
      <div className="segmented" role="group" aria-label="Per">
        {UNITS.map(it => (
          <button key={it} type="button" aria-pressed={it === unit} disabled={!allowed.includes(it) && it !== unit}
                  title={allowed.includes(it) ? `Per ${it}` : `Too few or too many ${it}s for this period`} onClick={() => onUnit(it)}>{UNIT_NAMES[it]}</button>
        ))}
      </div>
    </div>
  )
}
