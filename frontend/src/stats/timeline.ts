import type {Beatmap} from "../models/Types";
import type {Column} from "./stats";
import {overTime, perMonth} from "./stats";

export interface Period {
  /** A single year, or undefined for the last 12 months or all time */
  year?: number
  lastYear: boolean
}

export interface Timeline {
  unit: "month" | "year"
  columns: Column[]
  /** The column a date belongs in, -1 when it is outside of them */
  indexOf: (date: Date) => number
}

const monthOf = (date: Date) => date.getFullYear() * 12 + date.getMonth()

/** The same columns the overview counts in, so every tab speaks of the same months */
export function timeline(all: Beatmap[], {year, lastYear}: Period, now = new Date()): Timeline {
  const thisYear = now.getFullYear()
  const {columns, unit} = year
    ? {unit: "month" as const, columns: perMonth([], year * 12, year * 12 + (year === thisYear ? now.getMonth() : 11)).map(it => ({...it, short: it.label.slice(0, 3)}))}
    : overTime(all, lastYear, now)
  const keys = new Map(columns.map((it, index) => [it.key, index]))
  return {
    unit,
    columns: columns.map(it => ({...it, value: 0})),
    indexOf: date => keys.get(String(unit === "month" ? monthOf(date) : date.getFullYear())) ?? -1
  }
}

/** Puts every item in its column */
export function group<T>(line: Timeline, items: T[], dateOf: (item: T) => Date | undefined): T[][] {
  const groups = line.columns.map((): T[] => [])
  items.forEach(item => {
    const date = dateOf(item)
    const index = date ? line.indexOf(date) : -1
    if (index >= 0) groups[index].push(item)
  })
  return groups
}
