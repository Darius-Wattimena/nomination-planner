import type {Beatmap, Gamemode} from "../models/Types";
import type {Column} from "./stats";
import {nominatorsOf, rankedDate} from "./stats";
import type {Timeline} from "./timeline";
import {group} from "./timeline";

/** Different nominators on the beatmaps ranked in every column */
export function activeNominators(line: Timeline, maps: Beatmap[], mode?: Gamemode): Column[] {
  const groups = group(line, maps, rankedDate)
  return line.columns.map((column, index) => ({...column, value: new Set(groups[index].flatMap(it => nominatorsOf(it, mode)).map(it => it.osuId)).size}))
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

/** Nominators whose first ranked nomination is in each column */
export function newcomers(line: Timeline, firsts: Map<string, Date>): Column[] {
  const groups = group(line, Array.from(firsts.values()), it => it)
  return line.columns.map((column, index) => ({...column, value: groups[index].length}))
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
  const cumulative = ascending.reduce((all, it) => [...all, all[all.length - 1] + it], [0])
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

/** Different mappers in each column, split into those with their first ranked beatmap then and those who had one before */
export function mappersOverTime(line: Timeline, maps: Beatmap[], debuts: Set<string>): MapperColumn[] {
  const groups = group(line, maps, rankedDate)
  return line.columns.map((column, index) => {
    const first = new Set(groups[index].filter(it => debuts.has(it.osuId)).map(it => it.mapper.osuId))
    const all = new Set(groups[index].map(it => it.mapper.osuId))
    return {...column, value: all.size, first: first.size, returning: all.size - first.size}
  })
}
