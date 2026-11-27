import {useMemo} from "react";
import {useSearchParams} from "react-router";
import {useRanked} from "../resources/ranked";
import {modeParam} from "../utils/query";
import {inRange, parseRange, previous, resolve} from "./range";
import {debuts, rankedDate} from "./stats";

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

  return useMemo(() => {
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
    const firsts = new Set(debuts(all).map(it => it.osuId))
    return {
      loaded: !!maps,
      mode,
      range,
      before,
      ofMode,
      firsts,
      scope: ofMode.filter(it => inRange(rankedDate(it), range)),
      previousScope: before ? ofMode.filter(it => inRange(rankedDate(it), before)) : undefined
    }
  }, [maps, mode, key])
}
