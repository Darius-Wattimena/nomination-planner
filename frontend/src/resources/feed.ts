import type {Beatmap, BeatmapStatus, Gamemode, User} from "../models/Types";
import {MISSING_USER_ID} from "../Constants";
import {forget, readStored, store} from "../utils/storage";

/** What the backend sends: everything in progress, or only what changed since the token of an earlier update */
export interface FeedUpdate {
  token: string
  isComplete: boolean
  users: User[]
  beatmaps: FeedBeatmap[]
  removed: string[]
}

/** Users are only mentioned by their id */
interface FeedBeatmap {
  id: string
  artist: string
  title: string
  note?: string
  mapper: string
  mapperName?: string
  status: BeatmapStatus
  modes: {mode: Gamemode, by: {user: string, done?: boolean}[]}[]
  main?: Gamemode[]
  added: string | number
  updated: string | number
}

/** All updates so far put together */
export interface Feed {
  token: string
  users: Record<string, User>
  beatmaps: Record<string, FeedBeatmap>
}

export const NOBODY: User = {osuId: MISSING_USER_ID, username: "None", gamemodes: []}

export function applyUpdate(feed: Feed | undefined, update: FeedUpdate): Feed {
  const known = update.isComplete ? undefined : feed
  const users = {...known?.users}
  const beatmaps = {...known?.beatmaps}
  update.users.forEach(it => users[it.osuId] = it)
  update.beatmaps.forEach(it => beatmaps[it.id] = it)
  update.removed.forEach(it => delete beatmaps[it])
  return {token: update.token, users, beatmaps}
}

export function toBeatmap(feed: Feed, beatmap: FeedBeatmap): Beatmap {
  const user = (id: string, name = "Unknown"): User => id === MISSING_USER_ID ? NOBODY : feed.users[id] ?? {osuId: id, username: name, gamemodes: []}
  return {
    osuId: beatmap.id,
    artist: beatmap.artist,
    title: beatmap.title,
    note: beatmap.note ?? "",
    mapper: user(beatmap.mapper, beatmap.mapperName),
    status: beatmap.status,
    gamemodes: beatmap.modes.map(({mode, by}) => ({
      gamemode: mode,
      isReady: false,
      nominators: Array.from({length: Math.max(2, by.length)}, (_, index) => ({
        nominator: by[index] ? user(by[index].user) : NOBODY,
        hasNominated: !!by[index]?.done
      }))
    })),
    mainGamemodes: beatmap.main,
    dateAdded: beatmap.added,
    dateUpdated: beatmap.updated
  }
}

const KEY = "planner"

/** The last known state, to show right away when the planner is opened again */
export function readFeed(owner: string): Feed | undefined {
  const stored = readStored<{owner: string, feed?: Partial<Feed>}>(KEY)
  return stored?.owner === owner && stored.feed?.token && stored.feed.users && stored.feed.beatmaps ? stored.feed as Feed : undefined
}

export function storeFeed(owner: string, feed: Feed) {
  // Without it, only the next start is slower
  if (!store(KEY, {owner, feed})) forgetFeed()
}

export function forgetFeed() {
  forget(KEY)
}
