import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {activeNominators, firstNominations, lorenz, mappersOverTime, newcomers, nominationCounts, retention, topShare} from "./community";
import {timeline} from "./timeline";

const {Osu, Taiko} = Gamemode
const a = bn("1"), b = bn("2"), c = bn("3", Taiko)
const ranked = (osuId: string, date: string, modes = [mode(Osu, [a, true], [b, true])], mapper = user("100")) =>
  beatmap({osuId, dateRanked: date, gamemodes: modes, mapper})

const now = new Date(2026, 5, 15)
const line = timeline([], {year: 2026, lastYear: false}, now)
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
})
