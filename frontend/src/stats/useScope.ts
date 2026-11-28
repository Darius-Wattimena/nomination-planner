import {useMemo} from "react";
import {useSearchParams} from "react-router";
import type {Beatmap, Gamemode} from "../models/Types";
import {useRanked} from "../resources/ranked";
import {modeParam} from "../utils/query";
import {inRange, parseRange, previous, resolve} from "./range";
import {firstsOf, rankedDate} from "./stats";

function findScope(maps: Beatmap[] | undefined, mode: Gamemode | undefined, key: string) {
  const all = maps ?? []
  const ofMode = all.filter(it => !mode || it.gamemodes.some(own => own.gamemode === mode))
  const earliest = ofMode.reduce<Date | undefined>((first, it) => {
    const date = rankedDate(it)
    return date && (!first || date < first) ? date : first
  }, undefined)
  const range = resolve(key, earliest)
  const earlier = previous(range)
  // Only compared when something was ranked by then
  const before = earlier && earliest && earliest < earlier.to ? earlier : undefined
  return {
    loaded: !!maps,
    mode,
    range,
    before,
    ofMode,
    firsts: firstsOf(all),
    scope: ofMode.filter(it => inRange(rankedDate(it), range)),
    previousScope: before ? ofMode.filter(it => inRange(rankedDate(it), before)) : undefined
  }
}

/** The last few worked out, as the page and each of its tabs ask for the same one at once */
const recent: {maps: Beatmap[] | undefined, mode: Gamemode | undefined, key: string, scope: ReturnType<typeof findScope>}[] = []

function sharedScope(maps: Beatmap[] | undefined, mode: Gamemode | undefined, key: string) {
  const known = recent.find(it => it.maps === maps && it.mode === mode && it.key === key)
  if (known) return known.scope
  const scope = findScope(maps, mode, key)
  recent.unshift({maps, mode, key, scope})
  recent.length = Math.min(recent.length, 4)
  return scope
}

/**
 * The same range, mode and beatmaps the statistics page counts, read from the URL and the ranked beatmaps kept in
 * the browser. The statistics page and the tabs that compare with what came before use it, so they always agree.
 */
export function useScope() {
  const {maps} = useRanked()
  const [params] = useSearchParams()
  const chosen = modeParam(params.get("mode"))
  const mode = chosen === "all" ? undefined : chosen
  const key = parseRange(params.get("range"), params.get("period"))

  return useMemo(() => sharedScope(maps, mode, key), [maps, mode, key])
}
