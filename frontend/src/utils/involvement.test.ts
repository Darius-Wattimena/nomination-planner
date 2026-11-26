import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {inProgress, involvement, involvementOf} from "./involvement";

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

  it("counts what is in progress", () => {
    expect(inProgress(maps, "1")).toBe(1)
    expect(inProgress(maps, "3")).toBe(0)
  })
})
