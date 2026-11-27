import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {inProgress, involvement, involvementOf, summaryOf} from "./involvement";

describe("involvement", () => {
  const first = beatmap({osuId: "1", gamemodes: [mode(Gamemode.Osu, bn("1"), [bn("2"), true])]})
  const second = beatmap({osuId: "2", status: BeatmapStatus.Qualified, gamemodes: [mode(Gamemode.Osu, bn("1"), bn("3"))]})
  const maps = [first, second]

  it("lists the beatmaps of every nominator with their part", () => {
    expect(involvementOf(maps, "1")).toEqual([{map: first, part: "move"}, {map: second, part: "qualified"}])
    expect(involvementOf(maps, "2")).toEqual([{map: first, part: "waiting"}])
    expect(involvementOf(maps, "4")).toEqual([])
    expect(involvementOf(maps, undefined)).toEqual([])
    expect(involvement(maps).has("0")).toBe(false)
  })

  it("is worked out once for the same list", () => {
    expect(involvement(maps)).toBe(involvement(maps))
    expect(involvement([...maps])).not.toBe(involvement(maps))
  })

  it("leaves out archived beatmaps and lists a beatmap once", () => {
    const ranked = beatmap({osuId: "3", status: BeatmapStatus.Ranked, gamemodes: [mode(Gamemode.Osu, bn("1"), bn("2"))]})
    const hybrid = beatmap({osuId: "4", gamemodes: [mode(Gamemode.Osu, bn("1")), mode(Gamemode.Taiko, bn("1", Gamemode.Taiko))]})
    expect(involvementOf([ranked], "1")).toEqual([])
    expect(involvementOf([hybrid], "1").map(it => it.map)).toEqual([hybrid])
  })

  it("counts what is in progress", () => {
    expect(inProgress(maps, "1")).toBe(1)
    expect(inProgress(maps, "3")).toBe(0)
  })
})

describe("summaryOf", () => {
  it("counts what waits, what looks for a partner and what is qualified, leaving out what there is none of", () => {
    const map = beatmap()
    expect(summaryOf([{map, part: "move"}, {map, part: "move"}, {map, part: "partner"}, {map, part: "qualified"}, {map, part: "waiting"}]))
      .toBe("2 waiting for you · 1 looking for a second nominator · 1 qualified")
    expect(summaryOf([{map, part: "qualified"}])).toBe("1 qualified")
    expect(summaryOf([{map, part: "checking"}])).toBe("")
  })
})
