import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import {Beatmap, Gamemode, User} from "../models/Types";
import {isMissing, sortedModes} from "../utils/users";
import {toDate} from "../utils/workflow";

export interface Column {
  key: string
  /** Below the column */
  short: string
  /** In the tooltip and the table */
  label: string
  value: number
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

export function rankedDate(map: Beatmap) {
  return toDate(map.dateRanked)
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

/** Months while there are few of them, years once the months would become unreadable */
export function overTime(maps: Beatmap[], lastYearOnly: boolean, now = new Date()): {columns: Column[], unit: "month" | "year"} {
  const dates = maps.map(rankedDate).filter((it): it is Date => !!it && it <= now)
  if (lastYearOnly) return {columns: perMonth(maps, month(now) - 11, month(now)), unit: "month"}
  if (dates.length === 0) return {columns: [], unit: "month"}

  const first = Math.min(...dates.map(month))
  if (month(now) - first < 36) return {columns: perMonth(maps, first, month(now)), unit: "month"}

  const years = Array.from({length: now.getFullYear() - Math.floor(first / 12) + 1}, (_, index) => Math.floor(first / 12) + index)
  return {
    unit: "year",
    columns: years.map(year => ({key: String(year), short: String(year), label: String(year), value: dates.filter(it => it.getFullYear() === year).length}))
  }
}

export function nominatorsOf(map: Beatmap, mode?: Gamemode): User[] {
  const users = map.gamemodes
    .filter(it => !mode || it.gamemode === mode)
    .flatMap(it => it.nominators)
    .map(it => it.nominator)
    .filter(it => !isMissing(it))
  return users.filter((user, index) => users.findIndex(it => it.osuId === user.osuId) === index)
}

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

/** Who ranked beatmaps together: being on the same mode of the same beatmap */
export function network(maps: Beatmap[], only: Gamemode | undefined, limit: number) {
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
      if (ids.length !== 2) return
      const key = ids.join("-")
      const link = links.get(key) ?? {a: ids[0], b: ids[1], count: 0, modes: {}}
      links.set(key, link)
      add(link.modes, gamemode)
      if (!counted.has(key)) link.count++
      counted.add(key)
    })
  })

  const sorted = Array.from(people.values()).sort((a, b) => b.count - a.count || a.user.username.localeCompare(b.user.username))
  // Every mode gets its part, or the busiest mode would leave no room for the others
  const places = new Map<Gamemode, number>()
  const order = sorted.map((it, index) => {
    const main = GAMEMODES.reduce((best, mode) => (it.modes[mode] ?? 0) > (it.modes[best] ?? 0) ? mode : best)
    const place = places.get(main) ?? 0
    places.set(main, place + 1)
    return {it, place, index}
  })
  const shown = order.sort((a, b) => a.place - b.place || a.index - b.index).slice(0, limit)
    .sort((a, b) => a.index - b.index).map(({it}) => it)
  const ids = new Set(shown.map(it => it.user.osuId))
  return {
    people: shown,
    hidden: people.size - shown.length,
    links: Array.from(links.values())
      .filter(it => ids.has(it.a) && ids.has(it.b))
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
export function debuts(maps: Beatmap[]) {
  const first = new Map<string, Beatmap>()
  maps.filter(rankedDate).forEach(map => {
    const known = first.get(map.mapper.osuId)
    if (!known || rankedDate(map)! < rankedDate(known)!) first.set(map.mapper.osuId, map)
  })
  return Array.from(first.values()).sort((a, b) => rankedDate(b)!.getTime() - rankedDate(a)!.getTime())
}

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
