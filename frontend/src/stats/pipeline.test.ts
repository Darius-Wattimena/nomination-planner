import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {longestWaiting, ofMode, plannedLoad, stages, supply} from "./pipeline";

const {Osu, Taiko} = Gamemode
const now = new Date("2026-06-01T00:00:00Z").getTime()
const daysAgo = (days: number) => new Date(now - days * 86_400_000).toISOString()
const a = bn("1"), b = bn("2"), c = bn("3", Taiko)

const open = beatmap({osuId: "1", dateUpdated: daysAgo(3), gamemodes: [mode(Osu, a)]})
const hybrid = beatmap({osuId: "2", dateUpdated: daysAgo(40), gamemodes: [mode(Osu), mode(Taiko, c)]})
const planned = beatmap({osuId: "3", dateUpdated: daysAgo(10), gamemodes: [mode(Osu, a, b)]})
const qualified = beatmap({osuId: "4", status: BeatmapStatus.Qualified, dateUpdated: daysAgo(200), gamemodes: [mode(Osu, [a, true], [b, true])]})
const undated = beatmap({osuId: "5", dateUpdated: undefined, gamemodes: [mode(Osu)]})
const maps = [open, hybrid, planned, qualified, undated]

describe("pipeline", () => {
  it("filters on the mode", () => {
    expect(ofMode(maps, Taiko)).toEqual([hybrid])
    expect(ofMode(maps)).toBe(maps)
  })

  it("counts beatmaps per stage and mode, with how long they waited", () => {
    const byId = Object.fromEntries(stages(maps, now).map(it => [it.id, it]))
    expect(byId.open).toMatchObject({count: 3, days: 22, ages: [1, 0, 1, 0]})
    expect(byId.open.modes).toMatchObject({[Osu]: 3, [Taiko]: 1})
    expect(byId.checking).toMatchObject({count: 1, days: 10, ages: [0, 1, 0, 0]})
    expect(byId.qualified).toMatchObject({count: 1, ages: [0, 0, 0, 1]})
    expect(byId.attention).toMatchObject({count: 0, days: undefined})
  })

  it("finds the fewest nominators on half of what is planned", () => {
    expect(plannedLoad(maps)).toEqual({slots: 4, people: 1, nominators: 3})
  })

  it("compares open spots to free nominators", () => {
    expect(supply(maps, [a, b, c, bn("4")], Osu)).toEqual({mode: Osu, open: 4, team: 3, free: 1})
    expect(supply(maps, undefined, Taiko)).toEqual({mode: Taiko, open: 0, team: undefined, free: undefined})
  })

  it("finds what waits longest for a nominator", () => {
    expect(longestWaiting(maps).map(it => it.map.osuId)).toEqual(["2", "1"])
    expect(longestWaiting(maps, Osu, 1).map(it => it.map.osuId)).toEqual(["2"])
  })
})
