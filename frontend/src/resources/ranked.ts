import {useEffect, useSyncExternalStore} from "react";
import type {Beatmap, Gamemode} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import {useSession} from "../utils/session";
import Api from "./Api";
import {request} from "./client";
import {NOBODY} from "./feed";
import {forget} from "../utils/storage";
import {forgetLarge, readLarge, storeLarge} from "../utils/largeStorage";

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

/** Names of one letter and times in seconds, as it is sent for every ranked beatmap of the history */
interface RankedBeatmap {
  /** The id */
  i: string
  /** The title */
  t: string
  /** The id of the mapper */
  m: string
  /** When it was ranked */
  r?: number
  /** Who nominated each mode */
  n: {g: Gamemode, b: string[]}[]
  /** When it was added to the planner */
  a?: number
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
/** Raised when the backend sends more about every beatmap, so what is stored without it loads again in full once */
const SCHEMA = 3
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

/** What each ranked beatmap became, so one that didn't change stays the same object for whatever remembers it */
let made = new WeakMap<RankedBeatmap, Beatmap>()

function toBeatmaps({names, beatmaps}: Ranked): Beatmap[] {
  const name = (id: string) => names[id] ?? "Unknown"
  const user = (id: string) => ({osuId: id, username: name(id), gamemodes: []})
  // Unless someone in it got another name
  const isSame = (it: RankedBeatmap, beatmap: Beatmap) => beatmap.mapper.username === name(it.m) &&
    beatmap.gamemodes.every(mode => mode.nominators.every(({nominator, hasNominated}) => !hasNominated || nominator.username === name(nominator.osuId)))
  return Object.values(beatmaps).map(it => {
    const known = made.get(it)
    if (known && isSame(it, known)) return known
    const beatmap = toBeatmap(it, user)
    made.set(it, beatmap)
    return beatmap
  })
}

function toBeatmap(it: RankedBeatmap, user: (id: string) => Beatmap["mapper"]): Beatmap {
  return ({
    osuId: it.i,
    artist: "",
    title: it.t,
    note: "",
    mapper: user(it.m),
    status: BeatmapStatus.Ranked,
    gamemodes: it.n.map(({g: mode, b: by}) => ({
      gamemode: mode,
      nominators: Array.from({length: Math.max(2, by.length)}, (_, index) => by[index]
        ? {nominator: user(by[index]), hasNominated: true}
        : {nominator: NOBODY, hasNominated: false})
    })),
    dateAdded: it.a,
    dateRanked: it.r
  })
}

interface Stored {
  owner: string
  schema?: number
  ranked?: Ranked
}

// Kept in localStorage before, where every ranked beatmap of the history doesn't fit
forget(KEY)

function read(userId: string): Promise<Ranked | undefined> {
  return readLarge<Stored>(KEY)
    .then(stored => stored?.owner === userId && stored.schema === SCHEMA && stored.ranked?.token && hasShape(stored.ranked) ? stored.ranked : undefined)
}

/** What another version kept can be in an older shape, which loads again in full */
function hasShape(ranked: Ranked) {
  return Object.values(ranked.beatmaps).every(it => typeof it.i === "string" && Array.isArray(it.n))
}

function remember(userId: string, value: Ranked) {
  // Too large or not allowed in this browser, only opening the statistics again is slower
  void storeLarge(KEY, {owner: userId, schema: SCHEMA, ranked: value} satisfies Stored)
}

function load(userId: string) {
  let reading: Promise<void> = Promise.resolve()
  if (owner !== userId) {
    owner = userId
    ranked = undefined
    made = new WeakMap()
    loadedAt = 0
    set({error: false})
    reading = read(userId).then(stored => {
      if (owner !== userId || !stored) return
      ranked = stored
      set({maps: toBeatmaps(stored), error: false})
    })
  }
  if (loading || Date.now() - loadedAt < REFRESH_AFTER) return

  // What this browser kept is read first, so only what changed since is asked for
  loading = reading
    .then(() => request<RankedUpdate>(Api.fetchRanked(ranked?.token)))
    .then(update => {
      if (owner !== userId) return
      const known = update.isComplete ? undefined : ranked
      const beatmaps = {...known?.beatmaps}
      update.beatmaps.forEach(it => beatmaps[it.i] = it)
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
  void forgetLarge(KEY)
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
