import type {Beatmap} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import {partOf} from "./workflow";

export type TurnKind = "turn" | "disqualified" | "reset" | "qualified"

export interface TurnChange {
  kind: TurnKind
  map: Beatmap
}

const STATUS_KINDS: Partial<Record<BeatmapStatus, TurnKind>> = {
  [BeatmapStatus.Disqualified]: "disqualified",
  [BeatmapStatus.Reset]: "reset",
  [BeatmapStatus.Qualified]: "qualified"
}

/** How many of the others nominated */
function othersNominated(map: Beatmap, userId: string) {
  return map.gamemodes.flatMap(it => it.nominators).filter(it => it.hasNominated && it.nominator.osuId !== userId).length
}

/**
 * What changed for a nominator between two versions of the planner, at most one change per beatmap: it got disqualified,
 * reset or qualified, or it became their turn because someone else nominated. Nothing the first time, nor for beatmaps
 * that just showed up.
 */
export function turnChanges(before: Beatmap[] | undefined, after: Beatmap[] | undefined, userId: string | undefined): TurnChange[] {
  if (!before || !after || !userId) return []
  const known = new Map(before.map(it => [it.osuId, it]))

  return after.flatMap((map): TurnChange[] => {
    const previous = known.get(map.osuId)
    if (!previous || previous === map || !map.gamemodes.some(mode => mode.nominators.some(it => it.nominator.osuId === userId))) return []

    const kind = STATUS_KINDS[map.status]
    if (kind && previous.status !== map.status) return [{kind, map}]

    const myTurn = partOf(map, userId) === "move" && partOf(previous, userId) !== "move"
    return myTurn && othersNominated(map, userId) > othersNominated(previous, userId) ? [{kind: "turn", map}] : []
  })
}

const TITLES: Record<TurnKind, string> = {
  turn: "It's your turn",
  disqualified: "Disqualified",
  reset: "Nomination reset",
  qualified: "Qualified"
}

/** The words of a notification */
export function describeTurn({kind, map}: TurnChange): {title: string, body: string} {
  return {title: TITLES[kind], body: `${map.artist} - ${map.title}`}
}
