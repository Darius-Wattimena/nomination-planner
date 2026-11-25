import {useRef} from "react";
import {useSearchParams} from "react-router-dom";
import {GAMEMODES, PENDING_STATUSES} from "../Constants";
import {BeatmapFilter, BeatmapPage, BeatmapStatus, Gamemode, PageLimit, User} from "../models/Types";

export const PAGE_SIZES: Record<PageLimit, number> = {TEN: 10, TWENTY: 20, FIFTY: 50}

export interface ListState {
  search: string
  status: BeatmapStatus[]
  modes: Gamemode[]
  nominators: string[]
  missing: boolean
  pageNumber: number
  limit: PageLimit
  /** The opened beatmap */
  map?: string
}

/** The whole list state lives in the URL, so every view can be shared and survives a refresh */
export function useListState(page: BeatmapPage, user: User | undefined) {
  const [params, setParams] = useSearchParams()
  const latest = useRef(params)
  latest.current = params

  const list = (key: string) => params.get(key)?.split(",").filter(Boolean) ?? []
  const ownModes = GAMEMODES.filter(mode => user?.gamemodes.some(it => it.gamemode === mode))
  const pageNumber = Number(params.get("page"))
  const limit = params.get("limit")

  const state: ListState = {
    search: params.get("q") ?? "",
    status: page === "PENDING" ? PENDING_STATUSES.filter(it => list("status").includes(it)) : [],
    // Start with the modes of the user, until they pick something else
    modes: params.has("mode") ? GAMEMODES.filter(it => list("mode").includes(it)) : ownModes,
    nominators: list("nominators").filter(it => /^\d+$/.test(it)),
    missing: params.get("missing") === "1",
    pageNumber: Number.isInteger(pageNumber) && pageNumber > 1 ? pageNumber : 1,
    limit: limit && limit in PAGE_SIZES ? limit as PageLimit : "TWENTY",
    map: /^\d+$/.test(params.get("map") ?? "") ? params.get("map")! : undefined
  }

  const filter: BeatmapFilter = {
    page,
    search: state.search,
    status: state.status,
    gamemodes: state.modes,
    nominators: state.nominators,
    missingNominator: !state.missing ? [] : state.modes.length > 0 ? state.modes : GAMEMODES
  }

  /** Change the filters. Goes back to the first page, unless only the page or the opened beatmap changes. */
  function update(changes: Partial<ListState>) {
    const next = new URLSearchParams(latest.current)
    const set = (key: string, value: string | undefined) => value ? next.set(key, value) : next.delete(key)

    if ("search" in changes) set("q", changes.search)
    if ("status" in changes) set("status", changes.status?.join(","))
    if ("modes" in changes) next.set("mode", changes.modes?.join(",") || "all")
    if ("nominators" in changes) set("nominators", changes.nominators?.join(","))
    if ("missing" in changes) set("missing", changes.missing ? "1" : undefined)
    if ("limit" in changes) set("limit", changes.limit)
    if ("map" in changes) set("map", changes.map)

    if ("pageNumber" in changes) {
      set("page", changes.pageNumber && changes.pageNumber > 1 ? String(changes.pageNumber) : undefined)
    } else if (Object.keys(changes).some(key => key !== "map")) {
      next.delete("page")
    }

    latest.current = next
    // Opening a beatmap or page can be undone with the back button, filtering doesn't fill up the history
    setParams(next, {replace: !("map" in changes || "pageNumber" in changes)})
  }

  function clear() {
    update({search: "", status: [], modes: [], nominators: [], missing: false})
  }

  const isFiltered = state.search !== "" || state.status.length > 0 || state.modes.length > 0
    || state.nominators.length > 0 || state.missing

  return {state, filter, update, clear, isFiltered}
}
