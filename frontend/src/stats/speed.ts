import {GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import {toDate} from "../utils/workflow";
import type {Column} from "./stats";
import {median, rankedDate} from "./stats";
import type {Timeline} from "./timeline";
import {group} from "./timeline";

const DAY = 86_400_000

/** Whole days from being added to the planner until ranked, undefined when a date is missing or they are the wrong way around */
export function daysToRank(map: Beatmap): number | undefined {
  const added = toDate(map.dateAdded)
  const ranked = rankedDate(map)
  if (!added || !ranked || ranked < added) return undefined
  return Math.floor((ranked.getTime() - added.getTime()) / DAY)
}

export interface Timed {
  map: Beatmap
  days: number
}

export function timed(maps: Beatmap[]): Timed[] {
  return maps.flatMap(map => {
    const days = daysToRank(map)
    return days === undefined ? [] : [{map, days}]
  })
}

/** The median days for every column, where a column without beatmaps stays at 0 */
export function medianOverTime(line: Timeline, all: Timed[]): Column[] {
  const groups = group(line, all, it => rankedDate(it.map))
  return line.columns.map((column, index) => ({...column, value: median(groups[index].map(it => it.days)) ?? 0}))
}

export const SPEED_BUCKETS: {label: string, short: string, below: number}[] = [
  {label: "Within a week", short: "<1w", below: 7},
  {label: "1 to 2 weeks", short: "1-2w", below: 14},
  {label: "2 to 4 weeks", short: "2-4w", below: 28},
  {label: "1 to 2 months", short: "1-2mo", below: 61},
  {label: "2 to 3 months", short: "2-3mo", below: 91},
  {label: "3 to 6 months", short: "3-6mo", below: 183},
  {label: "Half a year or more", short: "6mo+", below: Infinity}
]

export function speedHistogram(all: Timed[]): Column[] {
  const columns = SPEED_BUCKETS.map(it => ({key: it.short, short: it.short, label: it.label, value: 0}))
  all.forEach(({days}) => columns[SPEED_BUCKETS.findIndex(it => days < it.below)].value++)
  return columns
}

/** A hybrid counts for each of its modes */
export function medianPerMode(all: Timed[], only?: Gamemode) {
  return GAMEMODES.filter(mode => !only || mode === only).map(mode => {
    const own = all.filter(it => it.map.gamemodes.some(own => own.gamemode === mode))
    return {mode, count: own.length, days: median(own.map(it => it.days))}
  })
}

export function extremes(all: Timed[], count = 5) {
  const sorted = [...all].sort((a, b) => a.days - b.days || a.map.osuId.localeCompare(b.map.osuId))
  return {fastest: sorted.slice(0, count), slowest: sorted.slice(-count).reverse()}
}

/** "3 days", "5 weeks", "4 months" */
export function duration(days: number) {
  const words = (count: number, unit: string) => `${count} ${unit}${count === 1 ? "" : "s"}`
  if (days < 14) return words(days, "day")
  if (days < 61) return words(Math.round(days / 7), "week")
  if (days < 730) return words(Math.round(days / 30.4), "month")
  return words(Math.round(days / 365), "year")
}
