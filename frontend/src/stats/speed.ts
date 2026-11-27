import {GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import {toDate} from "../utils/workflow";
import type {Column} from "./stats";
import {median, rankedDate} from "./stats";
import type {Bucket} from "./range";
import {group} from "./range";

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

/** The value below which a part `p` (0 to 1) of the values lies, between two values when it falls between them */
export function percentile(values: number[], p: number): number | undefined {
  if (values.length === 0) return undefined
  const sorted = [...values].sort((a, b) => a - b)
  const position = (sorted.length - 1) * Math.min(1, Math.max(0, p))
  const low = Math.floor(position)
  const high = Math.ceil(position)
  return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
}

/** Fewer beatmaps than this in a bucket say too little, so the bucket shows as a gap */
export const FEWEST = 3

export interface Spread {
  key: string
  short: string
  label: string
  current?: boolean
  count: number
  /** Whole days, undefined with fewer than FEWEST beatmaps */
  p25?: number
  median?: number
  p75?: number
}

const rounded = (value: number | undefined) => value === undefined ? undefined : Math.round(value)

/** The median days and the middle half around it for every bucket */
export function spreadOverTime(list: Bucket[], all: Timed[], fewest = FEWEST): Spread[] {
  return group(list, all, it => rankedDate(it.map)).map((own, index) => {
    const {key, short, label, current} = list[index]
    const days = own.length >= fewest ? own.map(it => it.days) : []
    return {key, short, label, current, count: own.length,
      p25: rounded(percentile(days, .25)), median: rounded(percentile(days, .5)), p75: rounded(percentile(days, .75))}
  })
}

/** The median days per bucket in every mode, a hybrid in each of its modes */
export function modeTrends(list: Bucket[], all: Timed[], only?: Gamemode, fewest = FEWEST) {
  return GAMEMODES.filter(mode => !only || mode === only).map(mode => {
    const own = all.filter(it => it.map.gamemodes.some(own => own.gamemode === mode))
    const spread = spreadOverTime(list, own, fewest)
    return {mode, count: own.length, medians: spread.map(it => it.median), counts: spread.map(it => it.count)}
  })
}

/** Beatmaps with one mode against those with more than one */
export function singleVsHybrid(all: Timed[]) {
  const of = (own: Timed[]) => ({count: own.length, days: median(own.map(it => it.days))})
  return {single: of(all.filter(it => it.map.gamemodes.length <= 1)), hybrid: of(all.filter(it => it.map.gamemodes.length > 1))}
}

/** The share ranked in less than `days` days after being added, undefined without beatmaps */
export function withinShare(all: Timed[], days: number): number | undefined {
  return all.length === 0 ? undefined : all.filter(it => it.days < days).length / all.length
}

export interface WithinPoint {
  key: string
  short: string
  label: string
  current?: boolean
  count: number
  /** Ranked within 7 days, undefined with fewer than FEWEST beatmaps */
  week?: number
  /** Ranked within 30 days */
  month?: number
}

/** The share ranked within a week and within a month for every bucket */
export function withinOverTime(list: Bucket[], all: Timed[], fewest = FEWEST): WithinPoint[] {
  return group(list, all, it => rankedDate(it.map)).map((own, index) => {
    const {key, short, label, current} = list[index]
    const enough = own.length >= fewest
    return {key, short, label, current, count: own.length,
      week: enough ? withinShare(own, 7) : undefined, month: enough ? withinShare(own, 30) : undefined}
  })
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
