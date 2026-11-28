import {GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import type {Column} from "./stats";
import {nominatorsOf, rankedDate} from "./stats";
import type {Bucket, TimeRange} from "./range";
import {columnOf, group} from "./range";

/** Different nominators on these beatmaps */
export function nominatorCount(maps: Beatmap[], mode?: Gamemode) {
  return new Set(maps.flatMap(it => nominatorsOf(it, mode)).map(it => it.osuId)).size
}

/** Different nominators on the beatmaps ranked in every bucket */
export function activeNominators(list: Bucket[], maps: Beatmap[], mode?: Gamemode): Column[] {
  return group(list, maps, rankedDate).map((own, index) => columnOf(list[index], nominatorCount(own, mode)))
}

/** The date of the first ranked nomination of every nominator, from everything ever ranked */
export function firstNominations(maps: Beatmap[], mode?: Gamemode) {
  const first = new Map<string, Date>()
  maps.forEach(map => {
    const date = rankedDate(map)
    if (!date) return
    nominatorsOf(map, mode).forEach(user => {
      const known = first.get(user.osuId)
      if (!known || date < known) first.set(user.osuId, date)
    })
  })
  return first
}

/** Nominators whose first ranked nomination is in each bucket */
export function newcomers(list: Bucket[], firsts: Map<string, Date>): Column[] {
  return group(list, Array.from(firsts.values()), it => it).map((own, index) => columnOf(list[index], own.length))
}

const monthsBefore = (now: Date, months: number) => new Date(now.getFullYear(), now.getMonth() - months, now.getDate())

/** Of the nominators who started at least 6 months ago, how many still had something ranked in the last 3 months */
export function retention(maps: Beatmap[], firsts: Map<string, Date>, mode?: Gamemode, now = new Date()) {
  const started = monthsBefore(now, 6)
  const recent = monthsBefore(now, 3)
  const eligible = Array.from(firsts.entries()).filter(([, date]) => date <= started).map(([id]) => id)
  const active = new Set(maps.filter(it => {
    const date = rankedDate(it)
    return !!date && date >= recent && date <= now
  }).flatMap(it => nominatorsOf(it, mode)).map(it => it.osuId))
  const retained = eligible.filter(it => active.has(it)).length
  return {eligible: eligible.length, retained, share: eligible.length > 0 ? retained / eligible.length : undefined}
}

/** Ranked nominations per nominator, the most first */
export function nominationCounts(maps: Beatmap[], mode?: Gamemode) {
  const counts = new Map<string, number>()
  maps.forEach(map => nominatorsOf(map, mode).forEach(it => counts.set(it.osuId, (counts.get(it.osuId) ?? 0) + 1)))
  return Array.from(counts.values()).sort((a, b) => b - a)
}

/** The share of all nominations done by the busiest part of the nominators, at least one of them */
export function topShare(counts: number[], part: number) {
  const total = counts.reduce((sum, it) => sum + it, 0)
  if (total === 0) return undefined
  const top = counts.slice(0, Math.max(1, Math.ceil(counts.length * part)))
  return top.reduce((sum, it) => sum + it, 0) / total
}

/**
 * Points of a Lorenz curve: the share of the nominations (y) done by the least busy share of the nominators (x),
 * from (0, 0) to (1, 1). A straight diagonal would mean everyone did as much.
 */
export function lorenz(counts: number[], points = 20): {x: number, y: number}[] {
  const total = counts.reduce((sum, it) => sum + it, 0)
  if (total === 0) return []
  const ascending = [...counts].sort((a, b) => a - b)
  const cumulative = [0]
  ascending.forEach(it => cumulative.push(cumulative[cumulative.length - 1] + it))
  const steps = Math.min(points, ascending.length)
  return Array.from({length: steps + 1}, (_, index) => {
    const people = Math.round(index / steps * ascending.length)
    return {x: people / ascending.length, y: cumulative[people] / total}
  })
}

export interface MapperColumn extends Column {
  first: number
  returning: number
}

/** Different mappers in each bucket, split into those with their first ranked beatmap then and those who had one before */
export function mappersOverTime(list: Bucket[], maps: Beatmap[], debuts: Set<string>): MapperColumn[] {
  return group(list, maps, rankedDate).map((own, index) => {
    const first = new Set(own.filter(it => debuts.has(it.osuId)).map(it => it.mapper.osuId))
    const all = new Set(own.map(it => it.mapper.osuId))
    return {...columnOf(list[index], all.size), first: first.size, returning: all.size - first.size}
  })
}

const DAY = 86_400_000

/** How long without a ranked nomination counts as being away */
export const AWAY_MONTHS = 6

/** The same moment some calendar months earlier, the day kept within the month */
export function monthsBack(date: Date, months: number) {
  const target = new Date(date.getFullYear(), date.getMonth() - months, 1)
  const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  return new Date(target.getFullYear(), target.getMonth(), Math.min(date.getDate(), last),
    date.getHours(), date.getMinutes(), date.getSeconds(), date.getMilliseconds())
}

export interface Activity {
  user: User
  /** Every ranked nomination, oldest first */
  dates: Date[]
}

/** The dates of the ranked nominations of every nominator */
export function activity(maps: Beatmap[], mode?: Gamemode): Map<string, Activity> {
  const people = new Map<string, Activity>()
  maps.forEach(map => {
    const date = rankedDate(map)
    if (!date) return
    nominatorsOf(map, mode).forEach(user => {
      const known = people.get(user.osuId)
      if (known) known.dates.push(date)
      else people.set(user.osuId, {user, dates: [date]})
    })
  })
  people.forEach(it => it.dates.sort((a, b) => a.getTime() - b.getTime()))
  return people
}

/** Whether a range is long enough to say someone stopped: about 4 weeks or more */
export function canTellStopped(range: TimeRange) {
  return range.to.getTime() - range.from.getTime() >= 27 * DAY
}

export interface Mover {
  user: User
  /** When they joined or came back, or for those who stopped their last ranked nomination */
  date: Date
}

export interface Movement {
  /** Their first ranked nomination ever is in the range */
  joined: Mover[]
  /** Something ranked in the 6 months before the range, nothing in it. Undefined when the range is too short to tell. */
  stopped?: Mover[]
  /** Something ranked in the range after 6 months or more without */
  cameBack: Mover[]
}

/** Who joined, stopped and came back in a range */
export function movement(people: Map<string, Activity>, range: TimeRange): Movement {
  const awayFrom = monthsBack(range.from, AWAY_MONTHS)
  const joined: Mover[] = [], stopped: Mover[] = [], cameBack: Mover[] = []
  people.forEach(({user, dates}) => {
    const inside = dates.findIndex(it => it >= range.from && it < range.to)
    if (inside === 0) joined.push({user, date: dates[0]})
    if (inside < 0) {
      const last = dates.filter(it => it < range.from).pop()
      if (last && last >= awayFrom) stopped.push({user, date: last})
      return
    }
    const back = dates.findIndex((it, index) => index > 0 && it >= range.from && it < range.to && monthsBack(it, AWAY_MONTHS) >= dates[index - 1])
    if (back > 0) cameBack.push({user, date: dates[back]})
  })
  const oldest = (a: Mover, b: Mover) => a.date.getTime() - b.date.getTime() || a.user.osuId.localeCompare(b.user.osuId)
  return {
    joined: joined.sort(oldest),
    stopped: canTellStopped(range) ? stopped.sort((a, b) => oldest(b, a)) : undefined,
    cameBack: cameBack.sort(oldest)
  }
}

export interface ShareColumn extends Column {
  /** Ranked nominations in each mode, `value` is all of them */
  parts: {mode: Gamemode, value: number}[]
}

/** Ranked nominations per mode in every bucket: a nominator on a mode of a ranked beatmap is one */
export function modeShare(list: Bucket[], maps: Beatmap[]): ShareColumn[] {
  return group(list, maps, rankedDate).map((own, index) => {
    const parts = GAMEMODES.map(mode => ({mode, value: own.reduce((sum, map) => sum + nominatorsOf(map, mode).length, 0)}))
    return {...columnOf(list[index], parts.reduce((sum, it) => sum + it.value, 0)), parts}
  })
}

/** Different nominators in each mode */
export function nominatorsPerMode(maps: Beatmap[]) {
  return GAMEMODES.map(mode => ({mode, count: nominatorCount(maps, mode)}))
}

export const WORKLOAD_BINS: {short: string, label: string, from: number}[] = [
  {short: "1", label: "1 nomination", from: 1},
  {short: "2–4", label: "2 to 4 nominations", from: 2},
  {short: "5–9", label: "5 to 9 nominations", from: 5},
  {short: "10–19", label: "10 to 19 nominations", from: 10},
  {short: "20+", label: "20 or more nominations", from: 20}
]

/** Nominators by how many ranked nominations they had */
export function workloadSpread(counts: number[]): Column[] {
  const columns = WORKLOAD_BINS.map(it => ({key: it.short, short: it.short, label: it.label, value: 0}))
  counts.filter(it => it > 0).forEach(count => {
    columns[WORKLOAD_BINS.findLastIndex(it => count >= it.from)].value++
  })
  return columns
}
