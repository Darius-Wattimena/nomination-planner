import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {debuts, network, overTime, yearBefore} from "./stats";

const {Osu, Taiko} = Gamemode
const now = new Date(2026, 5, 15)
const ranked = (osuId: string, date: string, changes = {}) => beatmap({osuId, dateRanked: date, ...changes})

describe("debuts", () => {
  it("keeps the first ranked beatmap of every mapper, the latest first", () => {
    const other = user("200", {}, "Other")
    const first = ranked("1", "2024-03-15T12:00:00Z")
    const later = ranked("2", "2025-01-15T12:00:00Z")
    const otherFirst = ranked("3", "2025-06-15T12:00:00Z", {mapper: other})
    const undated = beatmap({osuId: "4", mapper: user("300")})

    expect(debuts([later, first, otherFirst, undated])).toEqual([otherFirst, first])
  })
})

describe("overTime", () => {
  it("shows the last 12 months", () => {
    const {columns, unit} = overTime([ranked("1", "2026-05-15T12:00:00Z"), ranked("2", "2025-01-15T12:00:00Z")], true, now)
    expect(unit).toBe("month")
    expect(columns).toHaveLength(12)
    expect(columns[columns.length - 2].value).toBe(1)
    expect(columns.reduce((sum, it) => sum + it.value, 0)).toBe(1)
  })

  it("shows months from the first beatmap while there are few of them", () => {
    const {columns, unit} = overTime([ranked("1", "2025-06-15T12:00:00Z")], false, now)
    expect(unit).toBe("month")
    expect(columns).toHaveLength(13)
    expect(columns[0].value).toBe(1)
  })

  it("shows years once there are too many months", () => {
    const maps = [ranked("1", "2021-04-15T12:00:00Z"), ranked("2", "2021-08-15T12:00:00Z"), ranked("3", "2026-02-15T12:00:00Z")]
    const {columns, unit} = overTime(maps, false, now)
    expect(unit).toBe("year")
    expect(columns.map(it => [it.key, it.value])).toEqual([["2021", 2], ["2022", 0], ["2023", 0], ["2024", 0], ["2025", 0], ["2026", 1]])
  })

  it("has nothing to show without dates", () => {
    expect(overTime([beatmap()], false, now)).toEqual({columns: [], unit: "month"})
  })
})

describe("network", () => {
  const first = beatmap({osuId: "1", gamemodes: [mode(Osu, bn("1"), bn("2"))]})
  const hybrid = beatmap({osuId: "2", gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, bn("1", Taiko), bn("3", Taiko))]})

  it("connects those on the same mode of a beatmap, the most active first", () => {
    const {people, links} = network([first, hybrid], undefined)
    expect(people.map(it => [it.user.osuId, it.count, it.modes])).toEqual([
      ["1", 2, {osu: 2, taiko: 1}],
      ["2", 2, {osu: 2}],
      ["3", 1, {taiko: 1}]
    ])
    expect(links.map(it => [it.a, it.b, it.count, it.mode])).toEqual([["1", "2", 2, Osu], ["1", "3", 1, Taiko]])
  })

  it("only counts one mode when asked", () => {
    const {people, links} = network([first, hybrid], Taiko)
    expect(people.map(it => it.user.osuId)).toEqual(["1", "3"])
    expect(links).toHaveLength(1)
  })
})

describe("yearBefore", () => {
  const maps = [ranked("1", "2025-03-15T12:00:00Z"), ranked("2", "2025-09-15T12:00:00Z"), ranked("3", "2024-05-15T12:00:00Z")]

  it("compares this year to the same part of last year", () => {
    expect(yearBefore(maps, 2026, now).map(it => it.osuId)).toEqual(["1"])
  })

  it("compares an earlier year to the whole year before", () => {
    expect(yearBefore(maps, 2025, now).map(it => it.osuId)).toEqual(["3"])
  })
})
