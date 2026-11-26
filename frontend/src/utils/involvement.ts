import {MISSING_USER_ID} from "../Constants";
import type {Beatmap} from "../models/Types";
import type {Part} from "./workflow";
import {partOf} from "./workflow";

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
        if (part) index.set(id, [...(index.get(id) ?? []), {map, part}])
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
