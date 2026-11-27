import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import type {Entry} from "./boards";
import {boards, boardsPerMode, movement, scores} from "./boards";

const {Osu, Taiko} = Gamemode

describe("boards", () => {
  const other = user("200", {}, "Other")
  const maps = [
    beatmap({osuId: "1", gamemodes: [mode(Osu, bn("1"), bn("2"))]}),
    beatmap({osuId: "2", mapper: other, gamemodes: [mode(Osu, bn("1"), bn("3"))]}),
    beatmap({osuId: "3", gamemodes: [mode(Osu, bn("1"), bn("4")), mode(Taiko, bn("2", Taiko))]}),
    beatmap({osuId: "4", gamemodes: [mode(Osu, bn("2"), bn("3"))]})
  ]
  const board = (id: string, firsts = new Set<string>()) => boards(scores(maps, firsts)).find(it => it.id === id)!

  it("shares a place between equal values and skips the places they take", () => {
    expect(board("volume").entries.map(it => [it.user.osuId, it.value, it.place])).toEqual([
      ["1", 3, 1],
      ["2", 3, 1],
      ["3", 2, 3],
      ["4", 1, 4]
    ])
  })

  it("counts different mappers, partners on the same mode, hybrids and debuts", () => {
    expect(board("variety").entries.map(it => [it.user.osuId, it.value])).toEqual([["1", 2], ["3", 2], ["2", 1], ["4", 1]])
    expect(board("partners").entries.find(it => it.user.osuId === "1")?.value).toBe(3)
    // On taiko by themselves, so nobody new
    expect(board("partners").entries.find(it => it.user.osuId === "2")?.value).toBe(2)
    expect(board("hybrids").entries.map(it => it.user.osuId)).toEqual(["1", "2", "4"])
    expect(board("scout", new Set(["2"])).entries.map(it => it.user.osuId)).toEqual(["1", "3"])
  })

  it("has the boards of every mode with someone on them", () => {
    const perMode = boardsPerMode(maps, new Set())
    expect(perMode.map(it => it.mode)).toEqual([Osu, Taiko])
    expect(perMode[1].boards.find(it => it.id === "volume")!.entries.map(it => it.user.osuId)).toEqual(["2"])
  })

  it("leaves out restricted users", () => {
    const restricted = {...bn("5"), restricted: true}
    const all = boards(scores([beatmap({gamemodes: [mode(Osu, restricted, bn("1"))]})], new Set()))
    expect(all.flatMap(it => it.entries).some(it => it.user.osuId === "5")).toBe(false)
  })
})

describe("more boards", () => {
  const ranked = (osuId: string, added: string, rankedAt: string, ...ids: string[]) =>
    beatmap({osuId, dateAdded: added, dateRanked: rankedAt, gamemodes: [mode(Osu, ...ids.map(id => bn(id)))]})

  it("ranks the fastest median from added to ranked, only with enough beatmaps", () => {
    const maps = [
      ...[1, 2, 3, 4, 5].map(day => ranked(`a${day}`, "2026-01-01T00:00:00Z", `2026-01-${String(day * 2 + 1).padStart(2, "0")}T00:00:00Z`, "1")),
      ...[1, 2, 3, 4, 5].map(day => ranked(`b${day}`, "2026-01-01T00:00:00Z", "2026-01-01T12:00:00Z", "2")),
      // Only four, so not on the board
      ...[1, 2, 3, 4].map(day => ranked(`c${day}`, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "3"))
    ]
    const speed = boards(scores(maps, new Set())).find(it => it.id === "speed")!
    expect(speed.lowerIsBetter).toBe(true)
    // Half a day rounds to 1 day, the median of 2, 4, 6, 8, 10 is 6
    expect(speed.entries.map(it => [it.user.osuId, it.value, it.place])).toEqual([["2", 1, 1], ["1", 6, 2]])
    expect(speed.unit(0)).toBe("same day")
  })

  it("counts the different weeks with something ranked", () => {
    const maps = [
      ranked("1", "2026-01-01", "2026-03-02T10:00:00", "1"),
      ranked("2", "2026-01-01", "2026-03-04T10:00:00", "1"),
      ranked("3", "2026-01-01", "2026-03-10T10:00:00", "1", "2")
    ]
    const steady = boards(scores(maps, new Set())).find(it => it.id === "steady")!
    expect(steady.entries.map(it => [it.user.osuId, it.value])).toEqual([["1", 2], ["2", 1]])
  })
})

describe("movement", () => {
  const entry = (osuId: string, place: number): Entry => ({user: user(osuId), value: 10 - place, place})

  it("tells who went up, down, stayed or is new", () => {
    const moves = movement([entry("1", 1), entry("2", 2), entry("3", 2), entry("4", 4)], [entry("2", 1), entry("1", 3), entry("3", 2)])
    expect(moves.get("1")).toEqual({kind: "up", by: 2})
    expect(moves.get("2")).toEqual({kind: "down", by: 1})
    expect(moves.get("3")).toEqual({kind: "same"})
    expect(moves.get("4")).toEqual({kind: "new"})
  })
})
