import {GAMEMODE_NAMES} from "../Constants";
import {Beatmap, BeatmapStatus, Gamemode, User} from "../models/Types";
import {canClaim, lacksFullNominator, modesMissing, needsFullNominator, openSpot, openSpots, othersAreDone, roleOf, spots} from "./nominations";
import {isMissing} from "./users";

export type Stage = "attention" | "open" | "checking" | "nominated" | "qualified" | "archived"

export const STAGES: {id: Exclude<Stage, "archived">, label: string, hint: string}[] = [
  {id: "attention", label: "Disqualified or reset", hint: "Has to be checked again"},
  {id: "open", label: "Needs nominators", hint: "At least one open spot"},
  {id: "checking", label: "Planned", hint: "All nominators are known"},
  {id: "nominated", label: "Nominated", hint: "Waiting for the next nomination"},
  {id: "qualified", label: "Qualified", hint: "Ranked soon"}
]

export function stageOf(map: Beatmap): Stage {
  switch (map.status) {
    case BeatmapStatus.Disqualified:
    case BeatmapStatus.Reset:
      return "attention"
    case BeatmapStatus.Nominated:
      return "nominated"
    case BeatmapStatus.Qualified:
      return "qualified"
    case BeatmapStatus.Ranked:
    case BeatmapStatus.Graved:
      return "archived"
    default:
      return openSpots(map) > 0 || map.gamemodes.some(it => lacksFullNominator(map, it)) ? "open" : "checking"
  }
}

export type Tone = "you" | "warn" | "open" | "calm" | "good"

/** What has to happen next, in a single sentence */
export function nextStep(map: Beatmap, userId: string | undefined): {text: string, tone: Tone} {
  if (map.status === BeatmapStatus.Ranked) return {text: "Ranked", tone: "good"}
  if (map.status === BeatmapStatus.Graved) return {text: "In the graveyard", tone: "calm"}
  if (map.status === BeatmapStatus.Qualified) return {text: "Qualified", tone: "good"}

  const invalid = map.gamemodes.find(it => lacksFullNominator(map, it))
  if (invalid) {
    const where = map.gamemodes.length > 1 ? ` for ${GAMEMODE_NAMES[invalid.gamemode]}` : ""
    return {text: `On probation. Needs a full nominator${where}`, tone: "warn"}
  }

  const prefix = stageOf(map) === "attention" ? `${map.status}. ` : ""
  const move = deskGroup(map, userId)
  if (move === "move") return {text: prefix ? `${prefix}Check it again` : "Your turn to nominate", tone: "you"}

  const open = openSpots(map)
  if (open > 0) {
    const modes = modesMissing(map)
    const where = map.gamemodes.length > 1 && modes.length === 1 ? ` for ${GAMEMODE_NAMES[modes[0].gamemode]}` : ""
    return {text: `${prefix}Needs ${open} ${modes.length > 0 && modes.every(it => needsFullNominator(map, it)) ? "full " : ""}${open === 1 ? "nominator" : "nominators"}${where}`, tone: prefix ? "warn" : "open"}
  }

  const waiting = map.gamemodes.flatMap(it => spots(map, it)).filter(it => !it.hasNominated && !isMissing(it.nominator)).map(it => it.nominator)
  const names = Array.from(new Set(waiting.map(it => it.osuId === userId ? "you" : it.username)))
  const text = names.length === 0 ? "Everyone nominated" : names.length > 2
    ? `Waiting for ${names.length} nominators`
    : `Waiting for ${names.join(" and ")}`
  return {text: prefix + text, tone: prefix ? "warn" : "calm"}
}

export type DeskGroup = "move" | "partner" | "checking" | "waiting" | "qualified"

/** Where a beatmap belongs on the desk of a nominator, or undefined when they are not involved */
export function deskGroup(map: Beatmap, userId: string | undefined): DeskGroup | undefined {
  const modes = map.gamemodes.filter(mode => mode.nominators.some(it => it.nominator.osuId === userId))
  if (!userId || modes.length === 0 || stageOf(map) === "archived") return undefined
  if (map.status === BeatmapStatus.Qualified) return "qualified"

  const groups = modes.map((mode): DeskGroup => {
    const mine = mode.nominators.find(it => it.nominator.osuId === userId)!
    const partner = mode.nominators.find(it => it !== mine)
    if (lacksFullNominator(map, mode)) return "partner"
    if (roleOf(map, mode) === "other" || (roleOf(map, mode) === "undecided" && partner && isMissing(partner.nominator))) {
      if (mine.hasNominated) return "waiting"
      return stageOf(map) === "attention" || othersAreDone(map, mode) ? "move" : "checking"
    }
    if (!mine.hasNominated && (partner?.hasNominated || stageOf(map) === "attention")) return "move"
    if (partner && isMissing(partner.nominator)) return "partner"
    return mine.hasNominated ? "waiting" : "checking"
  })
  return (["move", "partner", "checking", "waiting"] as const).find(it => groups.includes(it))
}

/** Open beatmaps this user could take, the ones that are closest to being done first */
export function suggestions(maps: Beatmap[], user: User, rankedWith: (name: string) => number, only?: Gamemode) {
  return maps
    .filter(map => !deskGroup(map, user.osuId))
    .flatMap(map => map.gamemodes
      .filter(mode => (!only || mode.gamemode === only) && canClaim(map, mode, user) && (!openSpot(map, mode)?.optional || openSpots(map) > 0))
      .slice(0, 1)
      .map(mode => {
        const partner = mode.nominators.map(it => it.nominator).find(it => !isMissing(it))
        const together = partner ? rankedWith(partner.username) : 0
        const nominated = mode.nominators.some(it => it.hasNominated)
        const reason = partner
          ? `${partner.username} ${nominated ? "already nominated" : `is looking for a ${needsFullNominator(map, mode) ? "full" : "second"} nominator`}${together > 0 ? `, you ranked ${together} together` : ""}`
          : "No nominators yet"
        return {map, mode: mode.gamemode, reason, score: (nominated ? 100 : 0) + (partner ? 40 : 0) + Math.min(together, 20) - Math.min(ageInDays(map), 30) / 2}
      }))
    .sort((a, b) => b.score - a.score)
}

export function toDate(value: string | number | undefined) {
  if (value === undefined) return undefined
  // Timestamps are in seconds
  const date = new Date(typeof value === "number" ? value * 1000 : value)
  // Imported beatmaps use 1970 for dates they do not have
  return Number.isNaN(date.getTime()) || date.getTime() <= 0 ? undefined : date
}

export function ageInDays(map: Beatmap) {
  const date = toDate(map.dateUpdated)
  return date ? Math.max(0, Math.floor((Date.now() - date.getTime()) / 86_400_000)) : 0
}

export const STALE_DAYS = 21

export function isStale(map: Beatmap) {
  return stageOf(map) !== "qualified" && ageInDays(map) >= STALE_DAYS
}

export function ageLabel(map: Beatmap) {
  if (!toDate(map.dateUpdated)) return ""
  const days = ageInDays(map)
  if (days === 0) return "today"
  if (days < 14) return `${days}d`
  if (days < 60) return `${Math.floor(days / 7)}w`
  if (days < 365) return `${Math.floor(days / 30)}mo`
  return `${Math.floor(days / 365)}y`
}
