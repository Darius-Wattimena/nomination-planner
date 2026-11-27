import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, mode} from "../test/builders";
import {daysToRank, duration, extremes, medianOverTime, medianPerMode, speedHistogram, timed} from "./speed";
import {timeline} from "./timeline";

const {Osu, Taiko} = Gamemode
const map = (osuId: string, added: string | undefined, ranked: string | undefined, modes = [mode(Osu)]) =>
  beatmap({osuId, dateAdded: added, dateRanked: ranked, gamemodes: modes})

describe("daysToRank", () => {
  it("counts whole days from added to ranked", () => {
    expect(daysToRank(map("1", "2026-01-01T00:00:00Z", "2026-01-11T12:00:00Z"))).toBe(10)
  })

  it("ignores missing dates and negative durations", () => {
    expect(daysToRank(map("1", undefined, "2026-01-11T00:00:00Z"))).toBeUndefined()
    expect(daysToRank(map("1", "2026-01-01T00:00:00Z", undefined))).toBeUndefined()
    expect(daysToRank(map("1", "2026-02-01T00:00:00Z", "2026-01-11T00:00:00Z"))).toBeUndefined()
    // Imported beatmaps use 1970 for a date they do not have
    expect(daysToRank(map("1", "1970-01-01T00:00:00Z", "2026-01-11T00:00:00Z"))).toBeUndefined()
  })
})

const maps = [
  map("1", "2026-01-01T00:00:00Z", "2026-01-04T00:00:00Z"),
  map("2", "2026-01-01T00:00:00Z", "2026-01-21T00:00:00Z", [mode(Osu), mode(Taiko)]),
  map("3", "2025-12-01T00:00:00Z", "2026-03-11T00:00:00Z", [mode(Taiko)]),
  map("4", undefined, "2026-03-11T00:00:00Z")
]

describe("speed", () => {
  it("keeps only beatmaps with a duration", () => {
    expect(timed(maps).map(it => it.days)).toEqual([3, 20, 100])
  })

  it("has the median per month", () => {
    const line = timeline([], {year: 2026, lastYear: false}, new Date(2026, 3, 15))
    expect(medianOverTime(line, timed(maps)).map(it => it.value)).toEqual([12, 0, 100, 0])
  })

  it("puts the durations in buckets", () => {
    expect(speedHistogram(timed(maps)).map(it => it.value)).toEqual([1, 0, 1, 0, 0, 1, 0])
  })

  it("has the median per mode, a hybrid in each of its modes", () => {
    expect(medianPerMode(timed(maps))).toEqual([
      {mode: Osu, count: 2, days: 12},
      {mode: Taiko, count: 2, days: 60},
      {mode: Gamemode.Catch, count: 0, days: undefined},
      {mode: Gamemode.Mania, count: 0, days: undefined}
    ])
    expect(medianPerMode(timed(maps), Taiko)).toHaveLength(1)
  })

  it("finds the fastest and the slowest", () => {
    const {fastest, slowest} = extremes(timed(maps), 2)
    expect(fastest.map(it => it.map.osuId)).toEqual(["1", "2"])
    expect(slowest.map(it => it.map.osuId)).toEqual(["3", "2"])
  })

  it("says a duration in words", () => {
    expect(duration(1)).toBe("1 day")
    expect(duration(13)).toBe("13 days")
    expect(duration(21)).toBe("3 weeks")
    expect(duration(100)).toBe("3 months")
    expect(duration(800)).toBe("2 years")
  })
})
