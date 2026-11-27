import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {activeNominators, activity, canTellStopped, firstNominations, lorenz, mappersOverTime, modeShare, monthsBack, movement, newcomers, nominationCounts, nominatorsPerMode, retention, topShare, workloadSpread} from "./community";
import {buckets, customRange, resolve} from "./range";

const {Osu, Taiko} = Gamemode
const a = bn("1"), b = bn("2"), c = bn("3", Taiko)
const ranked = (osuId: string, date: string, modes = [mode(Osu, [a, true], [b, true])], mapper = user("100")) =>
  beatmap({osuId, dateRanked: date, gamemodes: modes, mapper})

const now = new Date(2026, 5, 15)
const line = buckets(resolve("2026", undefined, now), "month", now)
const maps = [
  ranked("10", "2025-06-01T12:00:00Z", [mode(Osu, [a, true])]),
  ranked("11", "2026-01-10T12:00:00Z"),
  ranked("12", "2026-01-20T12:00:00Z", [mode(Osu, [a, true], [b, true]), mode(Taiko, [c, true])], user("200")),
  ranked("13", "2026-05-20T12:00:00Z", [mode(Osu, [b, true])], user("300"))
]

describe("community", () => {
  it("counts different nominators per month", () => {
    expect(activeNominators(line, maps).map(it => it.value)).toEqual([3, 0, 0, 0, 1, 0])
    expect(activeNominators(line, maps, Taiko).map(it => it.value)).toEqual([1, 0, 0, 0, 0, 0])
  })

  it("counts newcomers by their first ranked nomination", () => {
    const firsts = firstNominations(maps)
    expect(firsts.get("1")).toEqual(new Date("2025-06-01T12:00:00Z"))
    expect(newcomers(line, firsts).map(it => it.value)).toEqual([2, 0, 0, 0, 0, 0])
  })

  it("finds who is still ranking", () => {
    const firsts = firstNominations(maps)
    // Only nominator 1 started 6 months ago, and had nothing ranked in the last 3 months
    expect(retention(maps, firsts, undefined, now)).toEqual({eligible: 1, retained: 0, share: 0})
    expect(retention([], new Map(), undefined, now).share).toBeUndefined()
    expect(retention(maps, firsts, undefined, new Date(2026, 6, 25))).toEqual({eligible: 3, retained: 1, share: 1 / 3})
  })

  it("measures how much the busiest do", () => {
    const counts = nominationCounts(maps)
    expect(counts).toEqual([3, 3, 1])
    expect(topShare(counts, .1)).toBeCloseTo(3 / 7)
    expect(topShare(counts, .5)).toBeCloseTo(6 / 7)
    expect(topShare([], .1)).toBeUndefined()
  })

  it("draws a Lorenz curve from the least busy", () => {
    expect(lorenz([3, 1])).toEqual([{x: 0, y: 0}, {x: .5, y: .25}, {x: 1, y: 1}])
    expect(lorenz([])).toEqual([])
    expect(lorenz(Array.from({length: 100}, () => 1), 4).map(it => it.y)).toEqual([0, .25, .5, .75, 1])
  })

  it("splits mappers into first-timers and returning ones", () => {
    const columns = mappersOverTime(line, maps, new Set(["10", "12", "13"]))
    expect(columns[0]).toMatchObject({value: 2, first: 1, returning: 1})
    expect(columns[4]).toMatchObject({value: 1, first: 1, returning: 0})
  })

  it("goes back whole calendar months", () => {
    expect(monthsBack(new Date(2026, 7, 31, 10), 6)).toEqual(new Date(2026, 1, 28, 10))
    expect(monthsBack(new Date(2026, 2, 15), 6)).toEqual(new Date(2025, 8, 15))
  })

  it("finds who joined, stopped and came back", () => {
    const d = bn("4"), e = bn("5"), f = bn("6")
    const history = [
      // 1 has ranked since 2025 and again in the range: nothing to say
      ranked("20", "2025-12-01T12:00:00Z", [mode(Osu, [a, true])]),
      ranked("21", "2026-04-10T12:00:00Z", [mode(Osu, [a, true])]),
      // 4 ranked in the 6 months before, not in the range: stopped
      ranked("22", "2026-01-15T12:00:00Z", [mode(Osu, [d, true])]),
      // 5 last ranked long ago and came back in the range
      ranked("23", "2025-01-01T12:00:00Z", [mode(Osu, [e, true])]),
      ranked("24", "2026-04-20T12:00:00Z", [mode(Osu, [e, true])]),
      // 6 is new in the range, twice
      ranked("25", "2026-04-02T12:00:00Z", [mode(Osu, [f, true])]),
      ranked("26", "2026-04-25T12:00:00Z", [mode(Osu, [f, true])]),
      // 2 ranked long before the range only: neither
      ranked("27", "2024-06-01T12:00:00Z", [mode(Osu, [b, true])])
    ]
    const april = resolve(customRange("2026-04-01", "2026-04-30"))
    const people = activity(history)
    expect(people.get("5")?.dates).toHaveLength(2)
    const moved = movement(people, april)
    expect(moved.joined.map(it => it.user.osuId)).toEqual(["6"])
    expect(moved.joined[0].date).toEqual(new Date("2026-04-02T12:00:00Z"))
    expect(moved.stopped?.map(it => it.user.osuId)).toEqual(["4"])
    expect(moved.cameBack.map(it => it.user.osuId)).toEqual(["5"])
    expect(moved.cameBack[0].date).toEqual(new Date("2026-04-20T12:00:00Z"))
    // Too short to tell who stopped
    const week = resolve(customRange("2026-04-01", "2026-04-07"))
    expect(canTellStopped(week)).toBe(false)
    expect(movement(people, week).stopped).toBeUndefined()
    // A taiko-only view sees none of them
    expect(movement(activity(history, Taiko), april).joined).toEqual([])
  })

  it("counts a gap of 6 months inside the range as coming back", () => {
    const history = [ranked("30", "2026-01-05T12:00:00Z", [mode(Osu, [a, true])]), ranked("31", "2026-09-05T12:00:00Z", [mode(Osu, [a, true])])]
    const moved = movement(activity(history), resolve("2026", undefined, new Date(2026, 11, 1)))
    expect(moved.joined.map(it => it.user.osuId)).toEqual(["1"])
    expect(moved.cameBack.map(it => it.date)).toEqual([new Date("2026-09-05T12:00:00Z")])
  })

  it("splits nominations per bucket by mode", () => {
    const columns = modeShare(line, maps)
    expect(columns[0]).toMatchObject({value: 5, parts: [{mode: Osu, value: 4}, {mode: Taiko, value: 1}, {mode: Gamemode.Catch, value: 0}, {mode: Gamemode.Mania, value: 0}]})
    expect(columns[1].value).toBe(0)
    expect(columns[4].parts[0]).toEqual({mode: Osu, value: 1})
  })

  it("counts nominators per mode", () => {
    expect(nominatorsPerMode(maps).map(it => it.count)).toEqual([2, 1, 0, 0])
  })

  it("spreads nominators by their workload", () => {
    expect(workloadSpread([25, 20, 19, 10, 9, 5, 4, 2, 1, 1, 0]).map(it => it.value)).toEqual([2, 2, 2, 2, 2])
    expect(workloadSpread([]).map(it => it.value)).toEqual([0, 0, 0, 0, 0])
  })
})
