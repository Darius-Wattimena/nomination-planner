import type {Beatmap} from "../models/Types";
import {deskGroup} from "./workflow";

/** What a nominator currently has on their plate */
export function workload(maps: Beatmap[], userId: string) {
  const groups = maps.map(map => deskGroup(map, userId)).filter(it => it !== undefined)
  const count = (...wanted: string[]) => groups.filter(it => wanted.includes(it)).length
  return {
    toCheck: count("move", "checking", "partner"),
    waiting: count("waiting"),
    qualified: count("qualified"),
    active: count("move", "checking", "partner", "waiting")
  }
}
