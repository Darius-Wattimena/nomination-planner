import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, mode} from "../test/builders";
import {daysToRank, duration, extremes, medianPerMode, modeTrends, percentile, singleVsHybrid, speedHistogram, spreadOverTime, timed, withinOverTime, withinShare} from "./speed";
import {buckets, resolve} from "./range";

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

describe("percentile", () => {
  it("interpolates between values", () => {
    expect(percentile([], .5)).toBeUndefined()
    expect(percentile([7], .25)).toBe(7)
    expect(percentile([4, 1, 3, 2], .5)).toBe(2.5)
    expect(percentile([1, 2, 3, 4, 5], .25)).toBe(2)
    expect(percentile([1, 2, 3, 4, 5], .75)).toBe(4)
    expect(percentile([0, 10], .25)).toBe(2.5)
    expect(percentile([1, 2], 2)).toBe(2)
  })
})

describe("speed", () => {
  it("keeps only beatmaps with a duration", () => {
    expect(timed(maps).map(it => it.days)).toEqual([3, 20, 100])
  })

  it("has the median and middle half per month, a gap with too few beatmaps", () => {
    const now = new Date(2026, 3, 15)
    const line = buckets(resolve("2026", undefined, now), "month", now)
    expect(spreadOverTime(line, timed(maps), 2).map(it => it.median)).toEqual([12, undefined, undefined, undefined])
    expect(spreadOverTime(line, timed(maps), 1).map(it => it.median)).toEqual([12, undefined, 100, undefined])
    const january = spreadOverTime(line, timed(maps), 2)[0]
    expect(january).toMatchObject({count: 2, p25: 7, median: 12, p75: 16, current: false})
    // Fewer than 3 by default
    expect(spreadOverTime(line, timed(maps))[0]).toMatchObject({count: 2, median: undefined, p25: undefined})
  })

  it("follows each mode per month", () => {
    const now = new Date(2026, 3, 15)
    const line = buckets(resolve("2026", undefined, now), "month", now)
    const [osu, taiko] = modeTrends(line, timed(maps), undefined, 1)
    expect(osu).toMatchObject({mode: Osu, count: 2, medians: [12, undefined, undefined, undefined], counts: [2, 0, 0, 0]})
    expect(taiko.medians).toEqual([20, undefined, 100, undefined])
    expect(modeTrends(line, timed(maps), Taiko)).toHaveLength(1)
  })

  it("compares one mode with more than one", () => {
    expect(singleVsHybrid(timed(maps))).toEqual({single: {count: 2, days: 52}, hybrid: {count: 1, days: 20}})
    expect(singleVsHybrid([]).hybrid).toEqual({count: 0, days: undefined})
  })

  it("has the share ranked within a week and a month", () => {
    expect(withinShare(timed(maps), 7)).toBeCloseTo(1 / 3)
    expect(withinShare(timed(maps), 30)).toBeCloseTo(2 / 3)
    expect(withinShare([], 7)).toBeUndefined()
    const now = new Date(2026, 3, 15)
    const line = buckets(resolve("2026", undefined, now), "month", now)
    expect(withinOverTime(line, timed(maps), 2)[0]).toMatchObject({count: 2, week: .5, month: 1})
    expect(withinOverTime(line, timed(maps), 2)[2]).toMatchObject({count: 1, week: undefined, month: undefined})
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
