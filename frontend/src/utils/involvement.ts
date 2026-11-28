import {MISSING_USER_ID} from "../Constants";
import type {Beatmap} from "../models/Types";
import type {Part} from "./workflow";
import {LABELS, partOf} from "./workflow";

export interface Involvement {
  map: Beatmap
  part: Part
}

const known = new WeakMap<Beatmap[], Map<string, Involvement[]>>()

/** Every nominator with the beatmaps they are on, worked out once for each list of beatmaps */
export function involvement(maps: Beatmap[]): Map<string, Involvement[]> {
  let index = known.get(maps)
  if (!index) {
    index = new Map()
    for (const map of maps) {
      const ids = new Set(map.gamemodes.flatMap(mode => mode.nominators.map(it => it.nominator.osuId)))
      ids.delete(MISSING_USER_ID)
      for (const id of ids) {
        const part = partOf(map, id)
        if (!part) continue
        const own = index.get(id)
        if (own) own.push({map, part})
        else index.set(id, [{map, part}])
      }
    }
    known.set(maps, index)
  }
  return index
}

export function involvementOf(maps: Beatmap[], userId: string | undefined): Involvement[] {
  return userId ? involvement(maps).get(userId) ?? [] : []
}

/** How many beatmaps a nominator is working on, qualified ones are done for them */
export function inProgress(maps: Beatmap[], userId: string) {
  return involvementOf(maps, userId).filter(it => it.part !== "qualified").length
}

const COUNTED: [Part, string][] = [["move", LABELS.move.toLowerCase()], ["partner", "looking for a second nominator"], ["qualified", "qualified"]]

/** What is on your desk in a few words: "2 waiting for you · 3 qualified", leaving out what there is none of */
export function summaryOf(involvements: Involvement[]) {
  return COUNTED
    .map(([part, words]) => [involvements.filter(it => it.part === part).length, words] as const)
    .filter(([count]) => count > 0)
    .map(([count, words]) => `${count} ${words}`)
    .join(" · ")
}
