import {useEffect, useSyncExternalStore} from "react";
import {MISSING_USER_ID} from "../Constants";
import type {Beatmap, BeatmapGamemode, Gamemode, User} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import {getSession, setSession, useSession} from "../utils/session";
import {canNominateAs, highestRole} from "../utils/users";
import Api from "./Api";
import {find, logOutSession, request, statusOf, useQuery} from "./client";
import {forgetRanked} from "./ranked";
import type {Feed, FeedBeatmap, FeedUpdate} from "./feed";
import {applyUpdate, changesUsers, forgetFeed, NOBODY, readFeed, storeFeed, toBeatmap} from "./feed";

export {NOBODY}

/**
 * Everything that is in progress is kept in memory, so that searching, filtering and switching views is instant.
 * It asks the backend for what changed in the background. The archive (ranked and graveyard) is too large for this and stays on the server.
 */
interface PlannerState {
  maps?: Beatmap[]
  /** Everybody who can nominate */
  nominators?: User[]
  /** Opened beatmaps that are not in progress */
  others: Record<string, Beatmap>
  error: boolean
  refreshing: boolean
  loadedAt: number
}

const EMPTY: PlannerState = {others: {}, error: false, refreshing: false, loadedAt: 0}
// Only when waiting for changes doesn't work
const REFRESH_AFTER = 60_000
const RETRY_AFTER = 5_000

/** What the backend told us */
let state = EMPTY
/** The same, with the changes that are still to be sent */
let shown = EMPTY
/** Everything the backend told us until the token of the last update */
let feed: Feed | undefined
let owner: string | undefined
/** The reload that is running, so others wait for it instead of starting another */
let loading: Promise<void> | undefined
let changes = 0
const listeners = new Set<() => void>()

/** The beatmaps with the pending changes, made again only when one of them changed. Lists that stay the same keep what was worked out for them. */
let applied: {maps?: Beatmap[], others: Record<string, Beatmap>, pending: PendingChange[], shown: Pick<PlannerState, "maps" | "others">} | undefined

function set(next: Partial<PlannerState>) {
  state = {...state, ...next}
  if (pending.length === 0) {
    applied = undefined
    shown = state
  } else {
    if (!applied || applied.maps !== state.maps || applied.others !== state.others || applied.pending !== pending) {
      applied = {maps: state.maps, others: state.others, pending, shown: {
        maps: state.maps?.map(withPending),
        others: Object.fromEntries(Object.entries(state.others).map(([id, map]) => [id, withPending(map)]))
      }}
    }
    shown = {...state, ...applied.shown}
  }
  listeners.forEach(listener => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function receive(update: FeedUpdate) {
  const before = feed
  const usersChanged = changesUsers(before, update)
  feed = applyUpdate(feed, update)
  const nothingNew = before && !update.isComplete && update.beatmaps.length + update.users.length + update.removed.length === 0
  if (nothingNew) {
    set({error: false, loadedAt: Date.now()})
    return
  }
  set({...fromFeed(feed, update.isComplete || usersChanged), error: false, loadedAt: Date.now()})
  if (owner) storeFeed(owner, feed)
}

/** Beatmaps made from the feed, for as long as the feed keeps the same object for them and nobody changed */
let made = new WeakMap<FeedBeatmap, Beatmap>()

function fromFeed(feed: Feed, usersChanged = true): Pick<PlannerState, "maps" | "nominators"> {
  if (usersChanged) made = new WeakMap()
  return {
    maps: Object.values(feed.beatmaps).map(it => {
      let beatmap = made.get(it)
      if (!beatmap) {
        beatmap = toBeatmap(feed, it)
        made.set(it, beatmap)
      }
      return beatmap
    }),
    nominators: !usersChanged && state.nominators ? state.nominators
      : Object.values(feed.users).filter(it => canNominateAs(highestRole(it))).sort((a, b) => a.username.localeCompare(b.username))
  }
}

export function reloadPlanner(): Promise<void> {
  loading ??= load().finally(() => {
    loading = undefined
  })
  return loading
}

async function load() {
  set({refreshing: true})
  const changesBefore = changes
  const ownerBefore = owner
  try {
    const update = await request<FeedUpdate>(Api.fetchPlanner(feed?.token))
    // Don't put back data from before a change that was made in the meantime
    if (ownerBefore === owner && changesBefore === changes) receive(update)
  } catch {
    if (ownerBefore === owner) set({error: true})
  } finally {
    set({refreshing: false})
  }
  if (changesBefore !== changes || ownerBefore !== owner) await load()
}

/** Whether waiting for changes works, then there is no need to load everything again now and then */
let waiting = false

/** Changes show up as soon as the backend has them, for as long as the planner is visible */
async function listen(userId: string) {
  while (owner === userId && document.visibilityState === "visible") {
    const token = feed?.token
    if (!token || loading) {
      await reloadPlanner()
      if (!feed?.token) return
      continue
    }
    const changesBefore = changes
    try {
      const update = await request<FeedUpdate>(Api.waitForPlanner(token))
      waiting = true
      // Don't put back data from before a change that was made in the meantime
      if (owner === userId && changesBefore === changes && feed?.token === token) receive(update)
    } catch {
      waiting = false
      if (owner === userId) set({error: true})
      await new Promise(resolve => window.setTimeout(resolve, RETRY_AFTER))
    }
  }
}

let listening: string | undefined

function startListening(userId: string) {
  if (listening === userId) return
  listening = userId
  void listen(userId).finally(() => {
    if (listening !== userId) return
    listening = undefined
    waiting = false
  })
}

/** Keeps the data of the logged-in user fresh, used once by the shell */
export function usePlannerRefresh() {
  const userId = useSession()?.user?.osuId

  useEffect(() => {
    if (owner !== userId) {
      owner = userId
      pending.forEach(it => window.clearTimeout(it.timer))
      pending = []
      feed = userId ? readFeed(userId) : undefined
      set({...EMPTY, maps: undefined, nominators: undefined, ...(feed && fromFeed(feed))})
    }
    if (!userId) return

    const refresh = () => {
      if (document.visibilityState !== "visible") return
      startListening(userId)
      // Waiting for changes keeps it fresh, a full reload is what is left when that fails
      if (!(listening === userId && waiting) && Date.now() - state.loadedAt > REFRESH_AFTER) void reloadPlanner()
    }
    refresh()
    const timer = window.setInterval(refresh, REFRESH_AFTER)
    document.addEventListener("visibilitychange", refresh)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener("visibilitychange", refresh)
    }
  }, [userId])
}

export function usePlanner() {
  return useSyncExternalStore(subscribe, () => shown)
}

/** A beatmap of the planner, also when it is already in the archive. Asked for by itself when the planner doesn't know it (yet). */
export function useBeatmap(id: string) {
  const {maps, others} = usePlanner()
  const known = maps?.find(it => it.osuId === id) ?? others[id]
  const {data, loading: fetching, error} = useQuery<Beatmap>(known ? undefined : Api.fetchBeatmap(id), find)
  return {
    beatmap: known ?? data,
    loading: !known && fetching,
    error: !known && error
  }
}

export function putBeatmap(beatmap: Beatmap) {
  changes++
  const archived = beatmap.status === BeatmapStatus.Ranked || beatmap.status === BeatmapStatus.Graved
  const known = state.maps?.some(it => it.osuId === beatmap.osuId)
  const maps = archived
    ? state.maps?.filter(it => it.osuId !== beatmap.osuId)
    : known ? state.maps?.map(it => it.osuId === beatmap.osuId ? beatmap : it) : state.maps && [beatmap, ...state.maps]
  set({maps, others: {...state.others, [beatmap.osuId]: beatmap}})
}

async function refreshBeatmap(id: string) {
  const beatmap = await find<Beatmap>(Api.fetchBeatmap(id))
  if (beatmap) putBeatmap(beatmap)
  return beatmap
}

/** Asks osu! for the latest of the beatmap */
export async function syncBeatmap(id: string) {
  const beatmap = await request<Beatmap>(Api.syncBeatmap(id))
  putBeatmap(beatmap)
  return beatmap
}

export async function deleteBeatmap(id: string) {
  await request(Api.deleteBeatmap(id))
  changes++
  const others = {...state.others}
  delete others[id]
  set({maps: state.maps?.filter(it => it.osuId !== id), others})
}

interface PendingChange {
  mapId: string
  mode: Gamemode
  oldId: string
  user: User
  sending: boolean
  /** Sent, but the backend refused it */
  failed: boolean
  timer: number
  onError: (error: unknown) => void
}

export const UNDO_TIME = 7000

let pending: PendingChange[] = []
// One after the other, as a change can build on the one before it
let queue: Promise<unknown> = Promise.resolve()

function withPending(map: Beatmap): Beatmap {
  return pending.filter(it => it.mapId === map.osuId).reduce(applyChange, map)
}

function applyChange(map: Beatmap, {mode, oldId, user}: PendingChange): Beatmap {
  const known = map.gamemodes.find(it => it.gamemode === mode)
  const current: BeatmapGamemode = known ?? {gamemode: mode, nominators: [0, 1].map(() => ({nominator: NOBODY, hasNominated: false}))}
  const ids = current.nominators.map(it => it.nominator.osuId)
  // Already part of what the backend told us
  if (!ids.includes(oldId) || (user.osuId !== MISSING_USER_ID && ids.includes(user.osuId))) return map

  const nominators = current.nominators.map((it, index) => index === ids.indexOf(oldId) ? {nominator: user, hasNominated: false} : it)
  const others = map.gamemodes.filter(it => it.gamemode !== mode)
  const empty = nominators.every(it => it.nominator.osuId === MISSING_USER_ID)
  return {...map, gamemodes: empty && others.length > 0 ? others : [...others, {...current, nominators}]}
}

function send(change: PendingChange) {
  if (change.sending) return
  change.sending = true
  window.clearTimeout(change.timer)
  queue = queue
    .then(() => setNominator(change.mapId, change.mode, change.oldId, change.user.osuId))
    .catch(error => {
      change.failed = true
      change.onError(error)
    })
    .finally(() => {
      pending = pending.filter(it => it !== change)
      set({})
    })
}

/** Later changes that are still waiting and build on this one, as they replace who it put in the slot */
function dependingOn(change: PendingChange): PendingChange[] {
  // An open spot is no person, filling any open spot doesn't build on this change
  if (change.user.osuId === MISSING_USER_ID) return []
  const later = pending.slice(pending.indexOf(change) + 1)
  const direct = later.filter(it => !it.sending && it.mapId === change.mapId && it.mode === change.mode && it.oldId === change.user.osuId)
  return direct.flatMap(it => [it, ...dependingOn(it)])
}

/** Shows the change right away and sends it a little later. Returns how to undo it, which leaves no trace until it is sent. */
export function changeNominator(mapId: string, mode: Gamemode, oldId: string, user: User, onError: (error: unknown) => void) {
  const change: PendingChange = {mapId, mode, oldId, user, onError, sending: false, failed: false, timer: 0}
  change.timer = window.setTimeout(() => send(change), UNDO_TIME)
  pending = [...pending, change]
  set({})

  let undone = false
  return () => {
    if (undone) return
    undone = true
    const dropped = [change, ...dependingOn(change)].filter(it => !it.sending)
    dropped.forEach(it => window.clearTimeout(it.timer))
    pending = pending.filter(it => !dropped.includes(it))
    set({})
    // What was sent is taken back, unless the backend refused it anyway
    if (change.sending) {
      queue = queue.then(() => change.failed ? undefined : setNominator(mapId, mode, user.osuId, oldId)).catch(onError)
    }
  }
}

function sendChanges() {
  pending.forEach(send)
  return queue
}

export async function logOut() {
  await sendChanges()
  forgetFeed()
  forgetRanked()
  await logOutSession()
}

// Nothing may get lost by leaving
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") void sendChanges()
})
// Closing the page can cut a change off halfway, so the browser asks to stay while any are left
window.addEventListener("beforeunload", event => {
  if (pending.length === 0) return
  void sendChanges()
  event.preventDefault()
})

export class ConflictError extends Error {}

export async function setNominator(id: string, gamemode: Gamemode, oldId: string, newId: string) {
  try {
    putBeatmap(await request<Beatmap>(Api.updateNominator(id, gamemode, oldId, newId)))
  } catch (error) {
    // Refused when somebody else was first, or removed the beatmap
    if (statusOf(error) !== 409 && statusOf(error) !== 404) throw error
    await refreshBeatmap(id)
    throw new ConflictError()
  }
}

export async function saveNote(beatmap: Beatmap, note: string) {
  await request(Api.updateBeatmapNote(beatmap.osuId, note))
  putBeatmap({...(state.maps?.find(it => it.osuId === beatmap.osuId) ?? beatmap), note})
}

export async function addBeatmap(id: string, gamemodes: Gamemode[]) {
  // osu! may not know it
  const added = await find<Beatmap>(Api.addBeatmap(id, gamemodes))
  if (added) putBeatmap(added)
  return added
}

/** The modes someone without a role chose, only changing what the website shows them */
export async function saveInterests(gamemodes: Gamemode[]) {
  const user = await request<User>(Api.saveInterests(gamemodes))
  const session = getSession()
  if (session?.user?.osuId === user.osuId) setSession({...session, user})
  return user
}
