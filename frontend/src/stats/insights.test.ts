import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {activity, daysToRank, heatmap, insights, level, mappersHelped, modeShares, monthly, partnersOf, percent, percentile, speed, trend} from "./insights";

const {Osu, Taiko} = Gamemode
// A Monday
const now = new Date(2026, 5, 15, 12)
const day = (month: number, date: number) => new Date(2026, month, date, 10)
const ranked = (osuId: string, date: Date, changes = {}) => beatmap({osuId, dateRanked: date.toISOString(), dateAdded: undefined, ...changes})

describe("heatmap", () => {
  const map = heatmap([day(5, 15), day(5, 15), day(5, 10), day(4, 1), new Date(2025, 4, 31), new Date(2025, 5, 1, 10), day(5, 16)], now)

  it("covers the last 12 months until today in weeks from Monday", () => {
    const first = map.weeks[0]
    // July 2025 starts on a Tuesday
    expect(first.start).toEqual(new Date(2025, 5, 30))
    expect(first.days[0]).toBeUndefined()
    expect(first.days[1]?.date).toEqual(new Date(2025, 6, 1))
    const last = map.weeks[map.weeks.length - 1]
    expect(last.start).toEqual(new Date(2026, 5, 15))
    expect(last.days[0]?.count).toBe(2)
    expect(last.days.slice(1).every(it => it === undefined)).toBe(true)
  })

  it("counts every day and week inside it", () => {
    expect(map.total).toBe(4)
    expect(map.dayMax).toBe(2)
    expect(map.weekMax).toBe(2)
    expect(map.weeks.find(it => it.days.some(day => day?.key === "2026-6-10"))?.total).toBe(1)
  })

  it("has levels compared to the busiest", () => {
    expect([0, 1, 2, 3, 4, 9].map(it => level(it, 4))).toEqual([0, 1, 2, 3, 4, 4])
    expect(level(1, 10)).toBe(1)
    expect(level(3, 0)).toBe(0)
  })
})

describe("modeShares", () => {
  const maps = [
    ranked("1", day(4, 1), {gamemodes: [mode(Taiko, bn("1", Taiko), bn("2", Taiko))]}),
    ranked("2", day(3, 1), {gamemodes: [mode(Taiko, bn("2", Taiko), bn("3", Taiko))]}),
    ranked("3", day(3, 1), {gamemodes: [mode(Osu, bn("1"), bn("4")), mode(Taiko, bn("2", Taiko), bn("3", Taiko))]}),
    ranked("4", day(2, 1), {gamemodes: [mode(Taiko, bn("2", Taiko), bn("3", Taiko))]}),
    ranked("5", new Date(2024, 1, 1), {gamemodes: [mode(Taiko, bn("1", Taiko), bn("2", Taiko))]})
  ]

  it("is the part of a mode in the last 12 months, the busiest mode first", () => {
    expect(modeShares(maps, "1", now)).toEqual([
      {mode: Osu, own: 1, total: 1, share: 1},
      {mode: Taiko, own: 1, total: 4, share: .25}
    ])
  })

  it("counts every nominator of a mode", () => {
    expect(Object.fromEntries(activity(maps, Taiko, now))).toEqual({"1": 1, "2": 4, "3": 3})
  })

  it("shows small shares with a decimal", () => {
    expect([.042, .25, 0, 1, .01].map(percent)).toEqual(["4.2%", "25%", "0%", "100%", "1%"])
  })
})

describe("percentile", () => {
  it("is the part of the others with fewer", () => {
    expect(percentile([10, 4, 4, 2, 1], 4)).toBe(.5)
    expect(percentile([10, 4, 2, 1], 10)).toBe(1)
    expect(percentile([10, 4, 2, 1], 1)).toBe(0)
  })

  it("needs others", () => {
    expect(percentile([3], 3)).toBeUndefined()
    expect(percentile([], 0)).toBeUndefined()
  })
})

describe("trend", () => {
  it("compares the last 3 months to the 3 before", () => {
    expect(trend([day(5, 1), day(4, 1), day(3, 1), day(1, 1)], now)).toEqual({recent: 3, before: 1, direction: "up"})
    expect(trend([day(5, 1), day(1, 1), day(0, 1)], now)).toEqual({recent: 1, before: 2, direction: "down"})
  })

  it("calls a small difference among many the same", () => {
    const recent = Array.from({length: 11}, () => day(5, 1))
    const before = Array.from({length: 10}, () => day(1, 1))
    expect(trend([...recent, ...before], now).direction).toBe("same")
    expect(trend([], now)).toEqual({recent: 0, before: 0, direction: "same"})
  })

  it("leaves out what is older or later", () => {
    expect(trend([new Date(2025, 11, 1), day(5, 20)], now)).toEqual({recent: 0, before: 0, direction: "same"})
  })
})

describe("mappers and partners", () => {
  const other = user("200", {}, "Other")
  const maps = [
    ranked("1", day(5, 1), {gamemodes: [mode(Osu, bn("1"), bn("2"))]}),
    ranked("2", day(4, 1), {gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, bn("1", Taiko), bn("3", Taiko))]}),
    ranked("3", day(3, 1), {mapper: other, gamemodes: [mode(Osu, bn("1"), bn("4")), mode(Taiko, bn("5", Taiko), bn("6", Taiko))]})
  ]

  it("counts mappers and their debuts", () => {
    expect(mappersHelped(maps, new Set(["3"]))).toEqual({mappers: 2, debuts: 1, top: {user: maps[0].mapper, count: 2}})
  })

  it("counts partners on the same mode once per beatmap", () => {
    const {partners, top} = partnersOf(maps, "1")
    expect(partners).toBe(3)
    expect(top?.user.osuId).toBe("2")
    expect(top?.share).toBeCloseTo(2 / 3)
  })
})

describe("speed", () => {
  const timed = (osuId: string, days: number, gamemode = Osu) => ranked(osuId, day(4, 20), {
    dateAdded: new Date(day(4, 20).getTime() - days * 86_400_000).toISOString(),
    gamemodes: [mode(gamemode, bn("1", gamemode), bn("2", gamemode))]
  })

  it("is the median of whole days, compared to the whole mode", () => {
    const own = [10, 20, 30, 40, 50].map((days, index) => timed(String(index), days))
    const others = [100, 100, 100].map((days, index) => timed(`o${index}`, days))
    expect(daysToRank(own[0])).toBe(10)
    expect(speed(own, [...own, ...others], Osu, now)).toEqual({own: 30, everyone: 45, counted: 5})
  })

  it("needs to know when most were added", () => {
    const own = [...[10, 20, 30, 40, 50].map((days, index) => timed(String(index), days)), ...Array.from({length: 6}, (_, index) => ranked(`u${index}`, day(4, 1)))]
    expect(speed(own, own, Osu, now)).toBeUndefined()
    expect(daysToRank(ranked("x", day(4, 1)))).toBeUndefined()
  })
})

describe("insights", () => {
  const maps = [
    ranked("1", day(5, 1), {gamemodes: [mode(Taiko, bn("1", Taiko), bn("2", Taiko))]}),
    ranked("2", day(4, 1), {gamemodes: [mode(Taiko, bn("1", Taiko), bn("2", Taiko))]}),
    ranked("3", day(0, 1), {gamemodes: [mode(Taiko, bn("3", Taiko), bn("2", Taiko))]})
  ]

  it("speaks to somebody on their own profile", () => {
    const cards = insights(maps, "1", {self: true, name: "Me"}, now)
    expect(cards.map(it => it.key)).toEqual(["share", "percentile", "trend", "mappers", "partners"])
    expect(cards[0].text).toBe("You nominated 67% of the 3 osu!taiko beatmaps ranked in the last 12 months.")
    expect(cards[1].text).toBe("With 2 beatmaps, you were busier than 50% of the 3 osu!taiko nominators active in the last 12 months.")
    expect(cards[2].text).toBe("You nominated 2 beatmaps in the last 3 months, up from 0 in the 3 before.")
    expect(cards[4].text).toBe("You nominated with 1 person. 100% of your beatmaps were with User 2.")
  })

  it("names somebody else", () => {
    const cards = insights(maps, "3", {self: false, name: "Three"}, now)
    expect(cards[0].text).toBe("Three nominated 33% of the 3 osu!taiko beatmaps ranked in the last 12 months.")
    expect(cards.find(it => it.key === "partners")?.text).toBe("Three nominated with 1 person. 100% of Three's beatmaps were with User 2.")
  })

  it("has nothing for somebody who didn't rank anything lately", () => {
    expect(insights(maps, "9", {self: false, name: "Nine"}, now)).toEqual([])
  })
})

describe("monthly", () => {
  it("counts the last 12 months", () => {
    const columns = monthly([ranked("1", day(5, 1), {gamemodes: [mode(Osu, bn("1"))]}), ranked("2", new Date(2025, 0, 1), {gamemodes: [mode(Osu, bn("1"))]})], "1", now)
    expect(columns).toHaveLength(12)
    expect(columns[11].value).toBe(1)
    expect(columns.reduce((sum, it) => sum + it.value, 0)).toBe(1)
  })
})
