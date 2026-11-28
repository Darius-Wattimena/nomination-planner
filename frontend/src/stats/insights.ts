import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {displayName} from "../utils/users";
import {toDate} from "../utils/workflow";
import type {Column} from "./stats";
import {firstsOf, inLastYear, median, nominatedIndex, nominatorsOf, perMonth, rankedDate, topBy} from "./stats";

/** Personal numbers for the profile, all over the last 12 months like the rest of the statistics */

const DAY = 86_400_000
const month = (date: Date) => date.getFullYear() * 12 + date.getMonth()
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate())
// Through the calendar, so a change to summer time doesn't move a day
const addDays = (date: Date, days: number) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
/** Monday is 0 */
export const weekday = (date: Date) => (date.getDay() + 6) % 7
export const dayKey = (date: Date) => `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`

/** The first day of the 12 months that the statistics call the last year, the current month included */
export function lastYearStart(now = new Date()) {
  return new Date(now.getFullYear(), now.getMonth() - 11, 1)
}

/** Ranked beatmaps somebody nominated, the latest first */
export function nominatedBy(maps: Beatmap[], id: string): Beatmap[] {
  return nominatedIndex(maps).get(id) ?? []
}

export function lastYear(maps: Beatmap[], now = new Date()) {
  return maps.filter(it => inLastYear(it, now))
}

// Heatmap

export interface Day {
  date: Date
  key: string
  count: number
}

export interface Week {
  /** The Monday it starts on, which can be before the period */
  start: Date
  /** Monday first, nothing for the days outside the period */
  days: (Day | undefined)[]
  total: number
}

export interface Heatmap {
  weeks: Week[]
  /** The most on one day and in one week */
  dayMax: number
  weekMax: number
  total: number
}

/** Every day of the last 12 months until today, in weeks that start on Monday */
export function heatmap(dates: Date[], now = new Date()): Heatmap {
  const start = lastYearStart(now)
  const end = startOfDay(now)
  const counts = new Map<string, number>()
  dates.forEach(date => {
    const day = startOfDay(date)
    if (day < start || day > end) return
    counts.set(dayKey(day), (counts.get(dayKey(day)) ?? 0) + 1)
  })

  const weeks: Week[] = []
  for (let monday = addDays(start, -weekday(start)); monday <= end; monday = addDays(monday, 7)) {
    const days = Array.from({length: 7}, (_, index): Day | undefined => {
      const date = addDays(monday, index)
      return date < start || date > end ? undefined : {date, key: dayKey(date), count: counts.get(dayKey(date)) ?? 0}
    })
    weeks.push({start: monday, days, total: days.reduce((sum, it) => sum + (it?.count ?? 0), 0)})
  }
  const all = weeks.flatMap(it => it.days).map(it => it?.count ?? 0)
  return {
    weeks,
    dayMax: Math.max(0, ...all),
    weekMax: Math.max(0, ...weeks.map(it => it.total)),
    total: all.reduce((sum, it) => sum + it, 0)
  }
}

/** 0 for nothing, then 1 to 4 compared to the busiest */
export function level(count: number, max: number) {
  return count <= 0 || max <= 0 ? 0 : Math.min(4, Math.ceil(count / max * 4))
}

// Share and rank among the others

export interface ModeShare {
  mode: Gamemode
  own: number
  total: number
  share: number
}

/** Of everything ranked for a mode in the last 12 months, how much they nominated. Their busiest mode first */
export function modeShares(maps: Beatmap[], id: string, now = new Date()): ModeShare[] {
  const recent = lastYear(maps, now)
  return GAMEMODES.map(mode => {
    const forMode = recent.filter(map => map.gamemodes.some(it => it.gamemode === mode))
    const own = forMode.filter(map => nominatorsOf(map, mode).some(it => it.osuId === id)).length
    return {mode, own, total: forMode.length, share: forMode.length ? own / forMode.length : 0}
  }).filter(it => it.own > 0).sort((a, b) => b.own - a.own)
}

/** How many beatmaps of a mode every nominator ranked in the last 12 months */
export function activity(maps: Beatmap[], mode: Gamemode, now = new Date()) {
  const counts = new Map<string, number>()
  lastYear(maps, now).forEach(map => nominatorsOf(map, mode).forEach(it => counts.set(it.osuId, (counts.get(it.osuId) ?? 0) + 1)))
  return counts
}

/** The part of the others that did less, from 0 to 1. `counts` holds the value of the person too. Nothing without others */
export function percentile(counts: number[], value: number) {
  const others = [...counts]
  const own = others.indexOf(value)
  if (own >= 0) others.splice(own, 1)
  if (others.length === 0) return undefined
  return others.filter(it => it < value).length / others.length
}

// Trend

export interface Trend {
  recent: number
  before: number
  direction: "up" | "down" | "same"
}

/** The last 3 months against the 3 before, counted back from today so the current month isn't half empty */
export function trend(dates: Date[], now = new Date()): Trend {
  const back = (months: number) => new Date(now.getFullYear(), now.getMonth() - months, now.getDate(), now.getHours(), now.getMinutes(), now.getSeconds(), now.getMilliseconds())
  const middle = back(3)
  const start = back(6)
  const recent = dates.filter(it => it > middle && it <= now).length
  const before = dates.filter(it => it > start && it <= middle).length
  // A beatmap more or less among many is the same
  const close = Math.abs(recent - before) <= Math.floor(Math.max(recent, before) * .1)
  return {recent, before, direction: close ? "same" : recent > before ? "up" : "down"}
}

// Mappers and partners

export function mappersHelped(own: Beatmap[], firsts: Set<string>) {
  const mappers = topBy(own.map(it => it.mapper), it => it.osuId)
  const top = mappers[0] && mappers[0].count > 1 ? {user: mappers[0].item, count: mappers[0].count} : undefined
  return {mappers: mappers.length, debuts: own.filter(it => firsts.has(it.osuId)).length, top}
}

/** Who they were on the same mode of a beatmap with, once per beatmap */
export function partnersOf(own: Beatmap[], id: string) {
  const met = own.flatMap(map => {
    const users = map.gamemodes
      .filter(mode => mode.nominators.some(it => it.nominator.osuId === id))
      .flatMap(mode => nominatorsOf({...map, gamemodes: [mode]}))
      .filter(it => it.osuId !== id)
    return users.filter((user, index) => users.findIndex(it => it.osuId === user.osuId) === index)
  })
  const partners = topBy(met, it => it.osuId)
  const top = partners[0] && own.length ? {user: partners[0].item, count: partners[0].count, share: partners[0].count / own.length} : undefined
  return {partners: partners.length, top}
}

// Speed

/** Whole days from being added to the planner to being ranked */
export function daysToRank(map: Beatmap) {
  const added = toDate(map.dateAdded)
  const ranked = rankedDate(map)
  if (!added || !ranked || ranked < added) return undefined
  return Math.floor((ranked.getTime() - added.getTime()) / DAY)
}

const MIN_TIMED = 5

/** Only once most of their beatmaps know when they were added, a median of two says nothing */
export function speed(own: Beatmap[], maps: Beatmap[], mode: Gamemode, now = new Date()) {
  const timed = (list: Beatmap[]) => list.map(daysToRank).filter((it): it is number => it !== undefined)
  const ownDays = timed(own)
  if (ownDays.length < MIN_TIMED || ownDays.length < own.length / 2) return undefined
  const everyone = timed(lastYear(maps, now).filter(map => map.gamemodes.some(it => it.gamemode === mode)))
  if (everyone.length < MIN_TIMED) return undefined
  return {own: median(ownDays)!, everyone: median(everyone)!, counted: ownDays.length}
}

// Comparing

/** Ranked beatmaps per month of the last 12 months */
export function monthly(maps: Beatmap[], id: string, now = new Date()): Column[] {
  return perMonth(nominatedBy(maps, id), month(now) - 11, month(now))
}

const known = new WeakMap<Beatmap[], User[]>()

/** Everyone who nominated something ranked, by name */
export function knownNominators(maps: Beatmap[]): User[] {
  if (known.has(maps)) return known.get(maps)!
  const people = new Map<string, User>()
  maps.forEach(map => nominatorsOf(map).forEach(it => {
    if (it.username !== "Unknown") people.set(it.osuId, it)
  }))
  const sorted = Array.from(people.values()).sort((a, b) => a.username.localeCompare(b.username))
  known.set(maps, sorted)
  return sorted
}

// Sentences

export interface Voice {
  /** Their own profile, so "you" */
  self: boolean
  name: string
}

export interface Insight {
  key: string
  title: string
  figure: string
  text: string
}

export function percent(share: number) {
  const value = share * 100
  return `${value > 0 && value < 10 ? Number(value.toFixed(1)) : Math.round(value)}%`
}

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`

function words({self, name}: Voice) {
  return {
    who: self ? "You" : name,
    whoAfter: self ? "you" : name,
    whose: self ? "your" : `${name}'s`,
    was: self ? "were" : "was",
  }
}

/** A few short cards, each a plain sentence. Nothing when they didn't rank anything in the last 12 months */
export function insights(maps: Beatmap[], id: string, voice: Voice, now = new Date()): Insight[] {
  const all = nominatedBy(maps, id)
  const own = lastYear(all, now)
  if (own.length === 0) return []
  const {who, whoAfter, whose, was} = words(voice)
  const cards: Insight[] = []

  const shares = modeShares(maps, id, now)
  const main = shares[0]
  if (main) {
    const extra = shares[1] ? ` and ${percent(shares[1].share)} of ${GAMEMODE_NAMES[shares[1].mode]}` : ""
    cards.push({
      key: "share",
      title: `Share of ${GAMEMODE_NAMES[main.mode]}`,
      figure: percent(main.share),
      text: `${who} nominated ${percent(main.share)} of the ${plural(main.total, `${GAMEMODE_NAMES[main.mode]} beatmap`)} ranked in the last 12 months${extra}.`
    })

    const counts = activity(maps, main.mode, now)
    const rank = percentile(Array.from(counts.values()), counts.get(id) ?? 0)
    if (rank !== undefined) {
      const nominators = `${plural(counts.size, `${GAMEMODE_NAMES[main.mode]} nominator`)} active in the last 12 months`
      cards.push({
        key: "percentile",
        title: "Among the others",
        figure: rank === 1 ? "The busiest" : rank >= .5 ? `Top ${percent(Math.max(.01, 1 - rank))}` : percent(rank),
        text: rank > 0
          ? `With ${plural(main.own, "beatmap")}, ${whoAfter} ${was} busier than ${percent(rank)} of the ${nominators}.`
          : `With ${plural(main.own, "beatmap")}, nobody of the ${nominators} ranked fewer.`
      })
    }
  }

  const change = trend(all.map(it => rankedDate(it)!), now)
  if (change.recent + change.before > 0) {
    const versus = change.direction === "same" ? `about the same as the ${change.before} in the 3 before`
      : `${change.direction} from ${change.before} in the 3 before`
    const difference = change.recent - change.before
    cards.push({
      key: "trend",
      title: "Lately",
      figure: change.direction === "same" ? "Steady" : `${difference > 0 ? "+" : "−"}${Math.abs(difference)}`,
      text: `${who} nominated ${plural(change.recent, "beatmap")} in the last 3 months, ${versus}.`
    })
  }

  const firsts = firstsOf(maps)
  const helped = mappersHelped(own, firsts)
  const debutText = helped.debuts > 0 ? `, ${helped.debuts} of them to their first ranked beatmap` : ""
  const topMapper = helped.top ? ` Most often ${displayName(helped.top.user)}, ${plural(helped.top.count, "time")}.` : ""
  cards.push({
    key: "mappers",
    title: "Mappers helped",
    figure: String(helped.mappers),
    text: `${who} helped ${plural(helped.mappers, "mapper")}${debutText}.${topMapper}`
  })

  const together = partnersOf(own, id)
  if (together.top) {
    cards.push({
      key: "partners",
      title: "Partners",
      figure: String(together.partners),
      text: `${who} nominated with ${plural(together.partners, "person", "different people")}. ${percent(together.top.share)} of ${whose} beatmaps were with ${displayName(together.top.user)}.`
    })
  }

  const fast = main && speed(own, maps, main.mode, now)
  if (main && fast) {
    const compared = fast.own < fast.everyone ? "faster than" : fast.own > fast.everyone ? "slower than" : "the same as"
    cards.push({
      key: "speed",
      title: "Added to ranked",
      figure: plural(fast.own, "day"),
      text: `${voice.self ? "Your" : whose} beatmaps took a median of ${plural(fast.own, "day")} from being added to ranked, ${compared} the ${plural(fast.everyone, "day")} of all ${GAMEMODE_NAMES[main.mode]} beatmaps.`
    })
  }

  return cards
}
