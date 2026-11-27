import {GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {inProgress} from "../utils/involvement";
import {spots} from "../utils/nominations";
import {canNominate, isMissing} from "../utils/users";
import type {Stage} from "../utils/workflow";
import {ageInDays, byOldest, lookingForNominator, stageOf, STAGES, toDate} from "../utils/workflow";
import {median} from "./stats";

type Counts = Record<Gamemode, number>

const noCounts = (): Counts => Object.fromEntries(GAMEMODES.map(it => [it, 0])) as Counts

export const ofMode = (maps: Beatmap[], mode?: Gamemode) => mode ? maps.filter(map => map.gamemodes.some(it => it.gamemode === mode)) : maps

export interface StageCount {
  id: Exclude<Stage, "archived">
  label: string
  hint: string
  /** Beatmaps, a hybrid counts once */
  count: number
  /** A hybrid counts for each of its modes */
  modes: Counts
  /** Median days since the last update */
  days?: number
  /** How many waited <1w, 1-4w, 1-3mo and 3mo+ since their last update */
  ages: number[]
}

export const AGE_BUCKETS: {label: string, short: string, below: number}[] = [
  {label: "Less than a week", short: "<1w", below: 7},
  {label: "1 to 4 weeks", short: "1-4w", below: 28},
  {label: "1 to 3 months", short: "1-3mo", below: 90},
  {label: "3 months or more", short: "3mo+", below: Infinity}
]

/** Where beatmaps wait, with how long since their last update. A sync sets that to the date on osu!. */
export function stages(maps: Beatmap[], now = Date.now()): StageCount[] {
  return STAGES.map(stage => {
    const own = maps.filter(it => stageOf(it) === stage.id)
    const modes = noCounts()
    own.forEach(map => map.gamemodes.forEach(it => modes[it.gamemode]++))
    const known = own.filter(it => toDate(it.dateUpdated)).map(it => ageInDays(it, now))
    const ages = AGE_BUCKETS.map(() => 0)
    known.forEach(days => ages[AGE_BUCKETS.findIndex(it => days < it.below)]++)
    return {...stage, count: own.length, modes, days: median(known), ages}
  })
}

/** The fewest nominators that hold half of the planned nominations, so it shows when a few carry most of it */
export function plannedLoad(maps: Beatmap[]) {
  const slots = maps.filter(it => stageOf(it) !== "qualified")
    .flatMap(map => map.gamemodes.flatMap(it => spots(map, it))).filter(it => !isMissing(it.nominator) && !it.hasNominated)
  const perPerson = new Map<string, number>()
  slots.forEach(it => perPerson.set(it.nominator.osuId, (perPerson.get(it.nominator.osuId) ?? 0) + 1))
  const counts = Array.from(perPerson.values()).sort((a, b) => b - a)
  let carried = 0, people = 0
  while (people < counts.length && carried < slots.length / 2) carried += counts[people++]
  return {slots: slots.length, people, nominators: counts.length}
}

/** Spots that still need somebody in a mode, the optional second one of an undecided main mode left out */
export function openSpotsIn(maps: Beatmap[], mode: Gamemode) {
  return maps.flatMap(map => map.gamemodes.filter(it => it.gamemode === mode).flatMap(it => spots(map, it))).filter(it => isMissing(it.nominator) && !it.optional).length
}

export interface Supply {
  mode: Gamemode
  open: number
  /** Undefined until the nominators are known */
  team?: number
  /** Nominators of the mode without anything planned */
  free?: number
}

export function supply(maps: Beatmap[], nominators: User[] | undefined, mode: Gamemode): Supply {
  const team = nominators?.filter(it => canNominate(it, mode))
  return {mode, open: openSpotsIn(maps, mode), team: team?.length, free: team?.filter(it => inProgress(maps, it.osuId) === 0).length}
}

/** The beatmaps with an open spot that have waited the longest since their last update */
export function longestWaiting(maps: Beatmap[], mode?: Gamemode, count = 10) {
  return lookingForNominator(maps, mode).filter(it => toDate(it.map.dateUpdated)).sort((a, b) => byOldest(a.map, b.map)).slice(0, count)
}
