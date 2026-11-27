import {beforeEach, describe, expect, it, vi} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {bn} from "../test/builders";
import type {Feed, FeedUpdate} from "./feed";
import {applyUpdate, forgetFeed, NOBODY, readFeed, storeFeed, toBeatmap} from "./feed";

const BN = bn("1")
const map = (id: string, changes = {}) => ({
  id, artist: "Artist", title: `Title ${id}`, mapper: "10", mapperName: "Mapper", status: BeatmapStatus.Pending,
  modes: [{mode: Gamemode.Osu, by: [{user: "1", done: true}]}], added: "2026-01-01T00:00:00Z", updated: "2026-01-02T00:00:00Z",
  ...changes
})
const update = (changes: Partial<FeedUpdate>): FeedUpdate => ({token: "t", isComplete: false, users: [], beatmaps: [], removed: [], ...changes})

describe("applyUpdate", () => {
  const start = applyUpdate(undefined, update({token: "t1", isComplete: true, users: [BN], beatmaps: [map("1"), map("2")]}))

  it("starts from everything that is sent", () => {
    expect(start).toEqual({token: "t1", users: {"1": BN}, beatmaps: {"1": map("1"), "2": map("2")}})
  })

  it("adds, replaces and removes what changed", () => {
    const next = applyUpdate(start, update({token: "t2", beatmaps: [map("2", {title: "New"}), map("3")], removed: ["1"]}))

    expect(next.token).toBe("t2")
    expect(Object.keys(next.beatmaps)).toEqual(["2", "3"])
    expect(next.beatmaps["2"].title).toBe("New")
    expect(next.users).toEqual({"1": BN})
    // What was there before stays the same
    expect(Object.keys(start.beatmaps)).toEqual(["1", "2"])
  })

  it("forgets everything else when it gets everything again", () => {
    const next = applyUpdate(start, update({token: "t3", isComplete: true, beatmaps: [map("3")]}))

    expect(next).toEqual({token: "t3", users: {}, beatmaps: {"3": map("3")}})
  })
})

describe("toBeatmap", () => {
  const feed: Feed = {token: "t", users: {"1": BN}, beatmaps: {}}

  it("turns ids into users and always has two nominators per mode", () => {
    const beatmap = toBeatmap(feed, map("1", {note: "Hi", main: [Gamemode.Osu]}))

    expect(beatmap).toEqual({
      osuId: "1",
      artist: "Artist",
      title: "Title 1",
      note: "Hi",
      mapper: {osuId: "10", username: "Mapper", gamemodes: []},
      status: BeatmapStatus.Pending,
      gamemodes: [{gamemode: Gamemode.Osu, nominators: [{nominator: BN, hasNominated: true}, {nominator: NOBODY, hasNominated: false}]}],
      mainGamemodes: [Gamemode.Osu],
      dateAdded: "2026-01-01T00:00:00Z",
      dateUpdated: "2026-01-02T00:00:00Z"
    })
  })

  it("copes with unknown and missing users", () => {
    const beatmap = toBeatmap(feed, map("1", {mapperName: undefined, modes: [{mode: Gamemode.Osu, by: [{user: "0"}, {user: "99"}, {user: "1"}]}]}))

    expect(beatmap.note).toBe("")
    expect(beatmap.mapper).toEqual({osuId: "10", username: "Unknown", gamemodes: []})
    expect(beatmap.gamemodes[0].nominators.map(it => it.nominator)).toEqual([NOBODY, {osuId: "99", username: "Unknown", gamemodes: []}, BN])
  })
})

describe("stored feed", () => {
  const feed: Feed = {token: "t", users: {"1": BN}, beatmaps: {"1": map("1")}}
  let storage: Map<string, string>

  beforeEach(() => {
    storage = new Map()
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key)
    })
  })

  it("is only read by the same user", () => {
    storeFeed("1", feed)

    expect(readFeed("1")).toEqual(feed)
    expect(readFeed("2")).toBeUndefined()
  })

  it("is ignored when it can't be read", () => {
    storage.set("planner", "{not json")
    expect(readFeed("1")).toBeUndefined()

    storage.set("planner", JSON.stringify({owner: "1", feed: {token: "t"}}))
    expect(readFeed("1")).toBeUndefined()
  })

  it("is ignored when an older version stored it", () => {
    storage.set("planner", JSON.stringify({owner: "1", feed}))
    expect(readFeed("1")).toBeUndefined()
  })

  it("is forgotten when the browser has no room for it", () => {
    storage.set("planner", "old")
    vi.stubGlobal("localStorage", {
      setItem: () => {
        throw new Error("QuotaExceededError")
      },
      removeItem: (key: string) => storage.delete(key)
    })

    storeFeed("1", feed)
    expect(storage.has("planner")).toBe(false)
  })

  it("can be forgotten", () => {
    storeFeed("1", feed)
    forgetFeed()

    expect(readFeed("1")).toBeUndefined()
  })
})
