import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {boards, scores} from "./boards";

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

  it("leaves out restricted users", () => {
    const restricted = {...bn("5"), restricted: true}
    const all = boards(scores([beatmap({gamemodes: [mode(Osu, restricted, bn("1"))]})], new Set()))
    expect(all.flatMap(it => it.entries).some(it => it.user.osuId === "5")).toBe(false)
  })
})
