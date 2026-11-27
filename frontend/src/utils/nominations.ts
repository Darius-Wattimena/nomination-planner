import type {Beatmap, BeatmapGamemode, BeatmapNominator, Gamemode, User} from "../models/Types";
import {canNominate, isMissing, isNominator, onProbation, sortedModes} from "./users";

/*
 * How beatmaps get qualified, see https://osu.ppy.sh/wiki/en/Beatmap_ranking_procedure
 *
 * The main mode needs two nominations and every other mode of a hybrid needs one. The planner always stores two
 * nominators per mode, so these decide which of them matter.
 */

const people = (mode: BeatmapGamemode) => mode.nominators.filter(it => !isMissing(it.nominator))

/** The modes that can be the main one, a single one when it is decided */
function mainModes(map: Beatmap): Gamemode[] {
  const possible = map.mainGamemodes?.length ? map.mainGamemodes : map.gamemodes.map(it => it.gamemode)
  if (possible.length < 2) return possible
  // Otherwise it's the one that is nominated first
  const nominated = map.gamemodes.filter(it => possible.includes(it.gamemode) && it.nominators.some(slot => slot.hasNominated))
  return nominated.length === 1 ? [nominated[0].gamemode] : possible
}

export type Role = "main" | "other" | "undecided"

export function roleOf(map: Beatmap, mode: BeatmapGamemode): Role {
  const main = mainModes(map)
  if (!main.includes(mode.gamemode)) return "other"
  return main.length === 1 ? "main" : "undecided"
}

/** Several modes on osu!, also when only one of them is on the planner */
export function isHybrid(map: Beatmap) {
  return map.gamemodes.length > 1 || map.gamemodes.some(it => roleOf(map, it) === "other")
}

/** While the main mode is undecided, one of the modes still gets a second nominator */
function secondIsTaken(map: Beatmap) {
  return map.gamemodes.some(it => roleOf(map, it) === "undecided" && people(it).length > 1)
}

/** How many nominations it takes, where the second one of an undecided main mode is counted separately */
function needed(map: Beatmap, mode: BeatmapGamemode) {
  const role = roleOf(map, mode)
  return role === "main" || (role === "undecided" && people(mode).length > 1) ? 2 : 1
}

export function neededNominations(map: Beatmap) {
  const undecided = map.gamemodes.some(it => roleOf(map, it) === "undecided") && !secondIsTaken(map)
  return map.gamemodes.reduce((sum, mode) => sum + needed(map, mode), 0) + (undecided ? 1 : 0)
}

/** Nominations that count, so not more than a mode needs */
export function doneNominations(map: Beatmap) {
  return map.gamemodes.reduce((sum, mode) =>
    sum + Math.min(roleOf(map, mode) === "other" ? 1 : 2, mode.nominators.filter(it => it.hasNominated).length), 0)
}

export interface Spot extends BeatmapNominator {
  /** The place in what is stored */
  index: number
  /** Only needed when this becomes the main mode */
  optional?: boolean
}

/** The spots that matter */
export function spots(map: Beatmap, mode: BeatmapGamemode): Spot[] {
  const all = mode.nominators.map((it, index): Spot => ({...it, index}))
  const taken = all.filter(it => !isMissing(it.nominator))
  const open = all.filter(it => isMissing(it.nominator))
  const role = roleOf(map, mode)
  if (role === "main") return all
  // Everybody who is on it is shown, even when that is more than needed
  const shown = taken.length > 0 ? taken : open.slice(0, 1)
  const second = open.find(it => !shown.includes(it))
  return role === "undecided" && second && !secondIsTaken(map) ? [...shown, {...second, optional: true}] : shown
}

/** Whether somebody can still join this mode */
export function openSpot(map: Beatmap, mode: BeatmapGamemode) {
  return spots(map, mode).find(it => isMissing(it.nominator))
}

export function openSpots(map: Beatmap) {
  const undecided = map.gamemodes.some(it => roleOf(map, it) === "undecided") && !secondIsTaken(map)
  return map.gamemodes.reduce((sum, mode) => sum + Math.max(0, needed(map, mode) - people(mode).length), 0) + (undecided ? 1 : 0)
}

/** The modes that miss a nominator for sure */
export function modesMissing(map: Beatmap) {
  return sortedModes(map).filter(mode => people(mode).length < needed(map, mode))
}

/** Every mode needs at least one full nominator, probationary ones can't nominate it on their own */
export function lacksFullNominator(map: Beatmap, mode: BeatmapGamemode) {
  const own = people(mode)
  // A single one can still get company while the main mode is undecided
  if (own.length < needed(map, mode) || (own.length < 2 && roleOf(map, mode) === "undecided" && !secondIsTaken(map))) return false
  return own.every(it => onProbation(it.nominator, mode.gamemode))
}

/** Whether only a full nominator can take the open spot */
export function needsFullNominator(map: Beatmap, mode: BeatmapGamemode) {
  if (!openSpot(map, mode)) return false
  const own = people(mode)
  return own.length === 0 ? roleOf(map, mode) === "other" : own.every(it => onProbation(it.nominator, mode.gamemode))
}

/** Whether everything besides this mode is nominated */
export function othersAreDone(map: Beatmap, mode: BeatmapGamemode) {
  return map.gamemodes.filter(it => it !== mode).every(it => it.nominators.filter(slot => slot.hasNominated).length >= needed(map, it))
}

/** Whether the user can join this mode */
export function canJoin(map: Beatmap, mode: BeatmapGamemode, user: User | undefined) {
  return !!user
    && map.status !== "Ranked"
    && map.status !== "Graved"
    && map.mapper.osuId !== user.osuId
    && canNominate(user, mode.gamemode)
    && !isNominator(mode, user.osuId)
    && !!openSpot(map, mode)
    && !(needsFullNominator(map, mode) && onProbation(user, mode.gamemode))
}
