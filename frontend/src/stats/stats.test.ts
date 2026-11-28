import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode, user} from "../test/builders";
import {debuts, network} from "./stats";

const {Osu, Taiko} = Gamemode
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

  it("makes every two of three nominators a pair, like on old ranked beatmaps", () => {
    const old = beatmap({osuId: "3", gamemodes: [mode(Osu, bn("1"), bn("2"), bn("4"))]})
    const {people, links} = network([old], undefined)
    expect(people).toHaveLength(3)
    expect(links.map(it => [it.a, it.b, it.count])).toEqual([["1", "2", 1], ["1", "4", 1], ["2", "4", 1]])
  })

  it("only counts one mode when asked", () => {
    const {people, links} = network([first, hybrid], Taiko)
    expect(people.map(it => it.user.osuId)).toEqual(["1", "3"])
    expect(links).toHaveLength(1)
  })
})
