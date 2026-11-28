import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {isMissing, sortedModes} from "../utils/users";
import {toDate} from "../utils/workflow";

export interface Column {
  key: string
  /** Below the column */
  short: string
  /** In the tooltip and the table */
  label: string
  value: number
  /** Still growing, drawn lighter */
  current?: boolean
}

const month = (date: Date) => date.getFullYear() * 12 + date.getMonth()

function monthColumn(index: number): Column {
  const date = new Date(Math.floor(index / 12), index % 12, 1)
  const january = index % 12 === 0
  return {
    key: String(index),
    short: january ? String(date.getFullYear()) : date.toLocaleDateString("en", {month: "short"}),
    label: date.toLocaleDateString("en", {month: "long", year: "numeric"}),
    value: 0
  }
}

const dates = new WeakMap<Beatmap, {value: Beatmap["dateRanked"], date: Date | undefined}>()

/** Asked for many times for each of tens of thousands of beatmaps, so worked out once for each */
export function rankedDate(map: Beatmap) {
  const known = dates.get(map)
  if (known && known.value === map.dateRanked) return known.date
  const date = toDate(map.dateRanked)
  dates.set(map, {value: map.dateRanked, date})
  return date
}

/**
 * Worked out once for each list of beatmaps, as several parts of a page ask for the same of every ranked beatmap.
 * A list that changes is a new list, so what was worked out for the old one is forgotten with it.
 */
function once<T>(work: (maps: Beatmap[]) => T): (maps: Beatmap[]) => T {
  const known = new WeakMap<Beatmap[], T>()
  return maps => {
    if (known.has(maps)) return known.get(maps)!
    const result = work(maps)
    known.set(maps, result)
    return result
  }
}

export function inLastYear(map: Beatmap, now = new Date()) {
  const date = rankedDate(map)
  return !!date && month(date) > month(now) - 12 && month(date) <= month(now)
}

export function perMonth(maps: Beatmap[], first: number, last: number): Column[] {
  const columns = Array.from({length: last - first + 1}, (_, index) => monthColumn(first + index))
  maps.forEach(map => {
    const date = rankedDate(map)
    const column = date && columns[month(date) - first]
    if (column) column.value++
  })
  return columns
}

export function nominatorsOf(map: Beatmap, mode?: Gamemode): User[] {
  const users = map.gamemodes
    .filter(it => !mode || it.gamemode === mode)
    .flatMap(it => it.nominators)
    .map(it => it.nominator)
    .filter(it => !isMissing(it))
  const seen = new Set<string>()
  return users.filter(user => !seen.has(user.osuId) && !!seen.add(user.osuId))
}

/** Everyone a beatmap mentions with a name, as a mapper or a nominator, by id */
export const peopleOf = once(maps => {
  const people = new Map<string, User>()
  maps.forEach(map => [map.mapper, ...map.gamemodes.flatMap(mode => mode.nominators.map(it => it.nominator))].forEach(it => {
    if (!isMissing(it) && it.username !== "Unknown") people.set(it.osuId, it)
  }))
  return people
})

type ModeCounts = Partial<Record<Gamemode, number>>

export interface Link {
  a: string
  b: string
  /** Beatmaps together */
  count: number
  modes: ModeCounts
  /** The mode they worked on most */
  mode: Gamemode
}

function add(counts: ModeCounts, mode: Gamemode) {
  counts[mode] = (counts[mode] ?? 0) + 1
}

/** Every two of the given ids, each pair once, in the order given */
export function pairsOf(ids: string[]): [string, string][] {
  return ids.flatMap((a, index) => ids.slice(index + 1).map((b): [string, string] => [a, b]))
}

/** Who ranked beatmaps together: being on the same mode of the same beatmap, the most active first */
export function network(maps: Beatmap[], only: Gamemode | undefined) {
  const people = new Map<string, {user: User, count: number, modes: ModeCounts}>()
  const links = new Map<string, Omit<Link, "mode">>()

  maps.forEach(map => {
    nominatorsOf(map, only).forEach(user => {
      const known = people.get(user.osuId)
      if (known) known.count++
      else people.set(user.osuId, {user, count: 1, modes: {}})
    })
    // A pair counts once for every beatmap, also when they are on several of its modes
    const counted = new Set<string>()
    map.gamemodes.filter(it => !only || it.gamemode === only).forEach(({gamemode, nominators}) => {
      const ids = Array.from(new Set(nominators.map(it => it.nominator).filter(it => !isMissing(it)).map(it => it.osuId))).sort()
      ids.forEach(id => add(people.get(id)!.modes, gamemode))
      // Old ranked beatmaps can have three nominators on a mode, each two of them are a pair
      pairsOf(ids).forEach(([a, b]) => {
        const key = `${a}-${b}`
        const link = links.get(key) ?? {a, b, count: 0, modes: {}}
        links.set(key, link)
        add(link.modes, gamemode)
        if (!counted.has(key)) link.count++
        counted.add(key)
      })
    })
  })

  const sorted = Array.from(people.values()).sort((a, b) => b.count - a.count || a.user.username.localeCompare(b.user.username))
  return {
    people: sorted,
    links: Array.from(links.values())
      .map((it): Link => ({...it, mode: GAMEMODES.reduce((best, mode) => (it.modes[mode] ?? 0) > (it.modes[best] ?? 0) ? mode : best)}))
      .sort((a, b) => b.count - a.count)
  }
}

export function combinations(maps: Beatmap[]) {
  const counts = new Map<string, {modes: Gamemode[], count: number}>()
  maps.filter(it => it.gamemodes.length > 1).forEach(map => {
    const modes = sortedModes(map).map(it => it.gamemode)
    const key = modes.join()
    const known = counts.get(key)
    if (known) known.count++
    else counts.set(key, {modes, count: 1})
  })
  return Array.from(counts.values())
    .sort((a, b) => b.count - a.count)
    .map(it => ({...it, key: it.modes.join(), label: it.modes.map(mode => GAMEMODE_NAMES[mode]).join(" + ")}))
}

/** The first ranked beatmap of every mapper, the latest debut first */
export const debuts = once(maps => {
  const first = new Map<string, Beatmap>()
  maps.filter(rankedDate).forEach(map => {
    const known = first.get(map.mapper.osuId)
    if (!known || rankedDate(map)! < rankedDate(known)!) first.set(map.mapper.osuId, map)
  })
  return Array.from(first.values()).sort((a, b) => rankedDate(b)!.getTime() - rankedDate(a)!.getTime())
})

/** The beatmaps that were the first ranked one of their mapper */
export const firstsOf = once(maps => new Set(debuts(maps).map(it => it.osuId)))

/** Every nominator with what they nominated that was ranked, the newest first */
export const nominatedIndex = once(maps => {
  const index = new Map<string, Beatmap[]>()
  maps.forEach(map => {
    if (!rankedDate(map)) return
    nominatorsOf(map).forEach(user => {
      const own = index.get(user.osuId)
      if (own) own.push(map)
      else index.set(user.osuId, [map])
    })
  })
  index.forEach(own => own.sort((a, b) => rankedDate(b)!.getTime() - rankedDate(a)!.getTime()))
  return index
})

export function topBy<T>(items: T[], keyOf: (item: T) => string) {
  const counts = new Map<string, {item: T, count: number}>()
  items.forEach(item => {
    const known = counts.get(keyOf(item))
    if (known) known.count++
    else counts.set(keyOf(item), {item, count: 1})
  })
  return Array.from(counts.values()).sort((a, b) => b.count - a.count)
}

export function median(values: number[]) {
  if (values.length === 0) return undefined
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : Math.round((sorted[middle - 1] + sorted[middle]) / 2)
}

/** A round number above the highest value, with the steps on the way */
export function scale(max: number) {
  const rough = Math.max(1, max) / 4
  const power = 10 ** Math.floor(Math.log10(rough))
  const step = Math.max(1, [1, 2, 5, 10].map(it => it * power).find(it => it >= rough)!)
  const top = Math.max(step, Math.ceil(max / step) * step)
  return {top, ticks: Array.from({length: Math.round(top / step) + 1}, (_, index) => index * step)}
}

export interface Summary {
  ranked: number
  mappers: number
  nominators: number
  firsts: number
  hybrids: number
}

export function summarize(maps: Beatmap[], firsts: Set<string>, mode?: Gamemode): Summary {
  return {
    ranked: maps.length,
    mappers: new Set(maps.map(it => it.mapper.osuId)).size,
    nominators: new Set(maps.flatMap(it => nominatorsOf(it, mode)).map(it => it.osuId)).size,
    firsts: maps.filter(it => firsts.has(it.osuId)).length,
    hybrids: maps.filter(it => it.gamemodes.length > 1).length
  }
}

export function inYear(map: Beatmap, year: number) {
  return rankedDate(map)?.getFullYear() === year
}

/** Which labels below columns to show: where each year starts in years of months, or a few spread out when there are many */
export function shownLabels(shorts: string[]) {
  const year = (it: string) => /^[0-9]{4}$/.test(it)
  if (shorts.length <= 14) return shorts.map(() => true)
  if (shorts.some(year) && !shorts.every(year)) return shorts.map(year)
  const step = Math.ceil(shorts.length / 7)
  return shorts.map((_, index) => index % step === 0)
}
