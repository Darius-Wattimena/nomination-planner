import {useEffect, useSyncExternalStore} from "react";
import type {Beatmap, Gamemode} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import {useSession} from "../utils/session";
import Api from "./Api";
import {request} from "./client";
import {NOBODY} from "./feed";
import {forget, readStored, store} from "../utils/storage";

/**
 * Every ranked beatmap with only what the statistics need. Kept in the browser and only what changed is asked for,
 * so the statistics open right away and switching between modes and periods doesn't need the backend.
 */
interface RankedUpdate {
  token: string
  isComplete: boolean
  names: Record<string, string>
  beatmaps: RankedBeatmap[]
  removed: string[]
}

interface RankedBeatmap {
  id: string
  title: string
  mapper: string
  ranked?: string | number
  modes: {mode: Gamemode, by: string[]}[]
}

interface Ranked {
  token: string
  names: Record<string, string>
  beatmaps: Record<string, RankedBeatmap>
}

interface State {
  maps?: Beatmap[]
  error: boolean
}

const KEY = "ranked"
const REFRESH_AFTER = 5 * 60_000

let owner: string | undefined
let ranked: Ranked | undefined
let state: State = {error: false}
let loadedAt = 0
let loading: Promise<void> | undefined
const listeners = new Set<() => void>()

function set(next: State) {
  state = next
  listeners.forEach(listener => listener())
}

function toBeatmaps({names, beatmaps}: Ranked): Beatmap[] {
  const user = (id: string) => ({osuId: id, username: names[id] ?? "Unknown", gamemodes: []})
  return Object.values(beatmaps).map(it => ({
    osuId: it.id,
    artist: "",
    title: it.title,
    note: "",
    mapper: user(it.mapper),
    status: BeatmapStatus.Ranked,
    gamemodes: it.modes.map(({mode, by}) => ({
      gamemode: mode,
      nominators: Array.from({length: Math.max(2, by.length)}, (_, index) => by[index]
        ? {nominator: user(by[index]), hasNominated: true}
        : {nominator: NOBODY, hasNominated: false})
    })),
    dateRanked: it.ranked
  }))
}

function read(userId: string): Ranked | undefined {
  const stored = readStored<{owner: string, ranked?: Ranked}>(KEY)
  return stored?.owner === userId && stored.ranked?.token ? stored.ranked : undefined
}

function remember(userId: string, value: Ranked) {
  // Too large for this browser, only opening the statistics again is slower
  if (!store(KEY, {owner: userId, ranked: value})) forget(KEY)
}

function load(userId: string) {
  if (owner !== userId) {
    owner = userId
    ranked = read(userId)
    loadedAt = 0
    set({maps: ranked && toBeatmaps(ranked), error: false})
  }
  if (loading || Date.now() - loadedAt < REFRESH_AFTER) return

  loading = request<RankedUpdate>(Api.fetchRanked(ranked?.token))
    .then(update => {
      if (owner !== userId) return
      const known = update.isComplete ? undefined : ranked
      const beatmaps = {...known?.beatmaps}
      update.beatmaps.forEach(it => beatmaps[it.id] = it)
      update.removed.forEach(it => delete beatmaps[it])
      const changed = !known || update.beatmaps.length + update.removed.length + Object.keys(update.names).length > 0
      ranked = {token: update.token, names: {...known?.names, ...update.names}, beatmaps}
      loadedAt = Date.now()
      if (changed) {
        set({maps: toBeatmaps(ranked), error: false})
        remember(userId, ranked)
      }
    })
    .catch(() => {
      if (owner === userId) set({...state, error: true})
    })
    .finally(() => {
      loading = undefined
    })
}

export function forgetRanked() {
  owner = undefined
  ranked = undefined
  forget(KEY)
}

export function useRanked() {
  const userId = useSession()?.user?.osuId
  const current = useSyncExternalStore(listener => {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }, () => state)

  useEffect(() => {
    if (userId) load(userId)
  }, [userId])

  return {
    maps: current.maps,
    // Old statistics are better than none
    error: current.error && !current.maps,
    retry: () => {
      if (!userId) return
      set({...state, error: false})
      loadedAt = 0
      load(userId)
    }
  }
}
