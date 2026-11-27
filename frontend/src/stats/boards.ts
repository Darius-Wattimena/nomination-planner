import {GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {bucketOf} from "./range";
import {median, nominatorsOf, rankedDate} from "./stats";
import {isMissing} from "../utils/users";
import {toDate} from "../utils/workflow";

export interface Entry {
  user: User
  value: number
  /** Shared by everyone with the same value */
  place: number
}

export interface Board {
  id: string
  title: string
  hint: string
  unit: (value: number) => string
  /** Like days to ranked, where the lowest is first */
  lowerIsBetter?: boolean
  entries: Entry[]
}

export interface Score {
  user: User
  maps: number
  firsts: number
  hybrids: number
  partners: Set<string>
  mappers: Set<string>
  /** Whole days from added to the planner to ranked, for the beatmaps that know both */
  waits: number[]
  /** The weeks something got ranked, by the time the week starts */
  weeks: Set<number>
}

/** Fewer beatmaps that know when they were added say little about how fast someone is */
export const MIN_WAITS = 5
const DAY = 86_400_000

export function scores(maps: Beatmap[], firsts: Set<string>, mode?: Gamemode): Score[] {
  const all = new Map<string, Score>()
  maps.forEach(map => {
    nominatorsOf(map, mode).forEach(user => {
      const score = all.get(user.osuId) ?? {user, maps: 0, firsts: 0, hybrids: 0, partners: new Set(), mappers: new Set(), waits: [], weeks: new Set()}
      all.set(user.osuId, score)
      score.maps++
      const added = toDate(map.dateAdded), ranked = rankedDate(map)
      if (added && ranked && ranked >= added) score.waits.push(Math.round((ranked.getTime() - added.getTime()) / DAY))
      if (ranked) score.weeks.add(bucketOf(ranked, "week").start.getTime())
      score.mappers.add(map.mapper.osuId)
      if (firsts.has(map.osuId)) score.firsts++
      if (map.gamemodes.length > 1) score.hybrids++
      // A partner is on the same mode
      map.gamemodes.filter(it => !mode || it.gamemode === mode)
        .filter(it => it.nominators.some(slot => slot.nominator.osuId === user.osuId))
        .flatMap(it => it.nominators)
        .forEach(({nominator}) => {
          if (!isMissing(nominator) && nominator.osuId !== user.osuId) score.partners.add(nominator.osuId)
        })
    })
  })
  // Restricted users have no name to show
  return Array.from(all.values()).filter(it => !it.user.restricted)
}

/** Nothing (undefined) and, unless the lowest is first, zero are left out */
function rank(all: Score[], valueOf: (score: Score) => number | undefined, lowerIsBetter = false): Entry[] {
  const sorted = all.map(it => ({user: it.user, value: valueOf(it)}))
    .filter((it): it is {user: User, value: number} => it.value !== undefined && (lowerIsBetter || it.value > 0))
    .sort((a, b) => (lowerIsBetter ? a.value - b.value : b.value - a.value) || a.user.username.localeCompare(b.user.username))
  // Sorted, so a shared place is the place of the first one with that value
  let place = 0
  return sorted.map((it, index) => {
    if (index === 0 || sorted[index - 1].value !== it.value) place = index + 1
    return {...it, place}
  })
}

const count = (one: string, many: string) => (value: number) => `${value} ${value === 1 ? one : many}`

/** Several ways to be good at this, so it isn't only about how much you do */
export function boards(all: Score[]): Board[] {
  return [
    {id: "scout", title: "Fresh faces", hint: "First ranked maps of new mappers", unit: count("first map", "first maps"), entries: rank(all, it => it.firsts)},
    {id: "variety", title: "Open door", hint: "Different mappers helped", unit: count("mapper", "mappers"), entries: rank(all, it => it.mappers.size)},
    {id: "partners", title: "Team player", hint: "Nominated with different people", unit: count("nominator", "nominators"), entries: rank(all, it => it.partners.size)},
    {id: "hybrids", title: "Mode hopper", hint: "Hybrids ranked", unit: count("hybrid", "hybrids"), entries: rank(all, it => it.hybrids)},
    {id: "volume", title: "Dedicated", hint: "Beatmaps ranked", unit: count("map", "maps"), entries: rank(all, it => it.maps)},
    {id: "speed", title: "Quick turnaround", hint: `Median days from added to ranked, at least ${MIN_WAITS} beatmaps`, lowerIsBetter: true,
      unit: value => value === 0 ? "same day" : count("day", "days")(value), entries: rank(all, it => it.waits.length >= MIN_WAITS ? median(it.waits) : undefined, true)},
    {id: "steady", title: "Steady hand", hint: "Different weeks with something ranked", unit: count("week", "weeks"), entries: rank(all, it => it.weeks.size)}
  ]
}

export type Move = {kind: "up" | "down", by: number} | {kind: "same" | "new"}

/** How everyone moved since the board before: up or down a number of places, the same, or not on it before */
export function movement(now: Entry[], before: Entry[]): Map<string, Move> {
  const places = new Map(before.map(it => [it.user.osuId, it.place]))
  return new Map(now.map(it => {
    const was = places.get(it.user.osuId)
    const move: Move = was === undefined ? {kind: "new"} : was === it.place ? {kind: "same"} : was > it.place ? {kind: "up", by: was - it.place} : {kind: "down", by: it.place - was}
    return [it.user.osuId, move]
  }))
}

/** The boards of every mode by itself, for the modes that have someone on them */
export function boardsPerMode(maps: Beatmap[], firsts: Set<string>): {mode: Gamemode, boards: Board[]}[] {
  return GAMEMODES
    .map(mode => ({mode, boards: boards(scores(maps.filter(map => map.gamemodes.some(it => it.gamemode === mode)), firsts, mode))}))
    .filter(it => it.boards.some(board => board.entries.length > 0))
}

export interface Badge {
  id: string
  title: string
  what: string
  steps: number[]
  value: number
  /** How many steps have been reached */
  level: number
  /** The next step, when there is one left */
  next?: number
}

/** Something to grow into, compared to yourself and not to others */
export function badges(score: Score | undefined): Badge[] {
  const definitions: [string, string, string, number[], number][] = [
    ["scout", "Fresh faces", "first ranked maps of new mappers", [1, 5, 15, 40], score?.firsts ?? 0],
    ["variety", "Open door", "different mappers helped", [5, 20, 50, 100], score?.mappers.size ?? 0],
    ["partners", "Team player", "nominators ranked with", [3, 8, 15, 30], score?.partners.size ?? 0],
    ["hybrids", "Mode hopper", "hybrids ranked", [1, 5, 15, 40], score?.hybrids ?? 0],
    ["volume", "Dedicated", "beatmaps ranked", [10, 50, 150, 400], score?.maps ?? 0]
  ]
  return definitions.map(([id, title, what, steps, value]) => {
    const level = steps.filter(it => value >= it).length
    return {id, title, what, steps, value, level, next: steps[level]}
  })
}
