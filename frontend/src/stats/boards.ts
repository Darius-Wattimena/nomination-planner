import type {Beatmap, Gamemode, User} from "../models/Types";
import {nominatorsOf} from "./stats";
import {isMissing} from "../utils/users";

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
  entries: Entry[]
}

interface Score {
  user: User
  maps: number
  firsts: number
  hybrids: number
  partners: Set<string>
  mappers: Set<string>
}

export function scores(maps: Beatmap[], firsts: Set<string>, mode?: Gamemode): Score[] {
  const all = new Map<string, Score>()
  maps.forEach(map => {
    nominatorsOf(map, mode).forEach(user => {
      const score = all.get(user.osuId) ?? {user, maps: 0, firsts: 0, hybrids: 0, partners: new Set(), mappers: new Set()}
      all.set(user.osuId, score)
      score.maps++
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

function rank(all: Score[], valueOf: (score: Score) => number): Entry[] {
  const sorted = all.map(it => ({user: it.user, value: valueOf(it)}))
    .filter(it => it.value > 0)
    .sort((a, b) => b.value - a.value || a.user.username.localeCompare(b.user.username))
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
    {id: "volume", title: "Dedicated", hint: "Beatmaps ranked", unit: count("map", "maps"), entries: rank(all, it => it.maps)}
  ]
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
