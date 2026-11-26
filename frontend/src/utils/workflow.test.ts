import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import type {User} from "../models/Types";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode, probation} from "../test/builders";
import {ageInDays, ageLabel, hiddenAsOld, isOld, lookingForNominator, OLD_DAYS, partOf, isStale, nextStep, stageOf, suggestions, toDate} from "./workflow";

const {Osu, Taiko} = Gamemode
const NOW = new Date("2026-06-01T12:00:00Z")
const daysAgo = (days: number) => new Date(NOW.getTime() - days * 86_400_000).toISOString()

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

describe("stageOf", () => {
  it("follows the status of osu!", () => {
    expect(stageOf(beatmap({status: BeatmapStatus.Disqualified}))).toBe("attention")
    expect(stageOf(beatmap({status: BeatmapStatus.Reset}))).toBe("attention")
    expect(stageOf(beatmap({status: BeatmapStatus.Nominated}))).toBe("nominated")
    expect(stageOf(beatmap({status: BeatmapStatus.Qualified}))).toBe("qualified")
    expect(stageOf(beatmap({status: BeatmapStatus.Ranked}))).toBe("archived")
    expect(stageOf(beatmap({status: BeatmapStatus.Graved}))).toBe("archived")
  })

  it("is planned once all nominators are known", () => {
    expect(stageOf(beatmap({gamemodes: [mode(Osu, bn("1"))]}))).toBe("open")
    expect(stageOf(beatmap({gamemodes: [mode(Osu, bn("1"), bn("2"))]}))).toBe("checking")
    expect(stageOf(beatmap({status: BeatmapStatus.Unfinished, gamemodes: [mode(Osu, bn("1"), bn("2"))]}))).toBe("checking")
  })

  it("stays open with only nominators on probation", () => {
    expect(stageOf(beatmap({gamemodes: [mode(Osu, probation("1"), probation("2"))]}))).toBe("open")
  })
})

describe("nextStep", () => {
  it("names the result for what is done", () => {
    expect(nextStep(beatmap({status: BeatmapStatus.Ranked}), undefined)).toEqual({text: "Ranked", tone: "good"})
    expect(nextStep(beatmap({status: BeatmapStatus.Graved}), undefined)).toEqual({text: "In the graveyard", tone: "calm"})
    expect(nextStep(beatmap({status: BeatmapStatus.Qualified}), undefined)).toEqual({text: "Qualified", tone: "good"})
  })

  it("warns when only nominators on probation are planned", () => {
    expect(nextStep(beatmap({gamemodes: [mode(Osu, probation("1"), probation("2"))]}), undefined))
      .toEqual({text: "On probation. Needs a full nominator", tone: "warn"})
    expect(nextStep(beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, probation("3", Taiko))]}), undefined))
      .toEqual({text: "On probation. Needs a full nominator for osu!taiko", tone: "warn"})
  })

  it("tells how many nominators are missing", () => {
    expect(nextStep(beatmap(), undefined)).toEqual({text: "Needs 2 nominators", tone: "open"})
    expect(nextStep(beatmap({gamemodes: [mode(Osu, probation("1"))]}), undefined)).toEqual({text: "Needs 1 full nominator", tone: "open"})
    expect(nextStep(beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko)]}), undefined))
      .toEqual({text: "Needs 1 full nominator for osu!taiko", tone: "open"})
    expect(nextStep(beatmap({status: BeatmapStatus.Disqualified}), undefined)).toEqual({text: "Disqualified. Needs 2 nominators", tone: "warn"})
  })

  it("tells a nominator when it is their turn", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1"), [bn("2"), true])]})

    expect(nextStep(map, "1")).toEqual({text: "Your turn to nominate", tone: "you"})
    expect(nextStep({...map, status: BeatmapStatus.Disqualified}, "1")).toEqual({text: "Disqualified. Check it again", tone: "you"})
  })

  it("names who is still to nominate", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1"), bn("2"))]})
    const hybrid = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, bn("3", Taiko))]})

    expect(nextStep(map, undefined)).toEqual({text: "Waiting for User 1 and User 2", tone: "calm"})
    expect(nextStep(map, "1")).toEqual({text: "Waiting for you and User 2", tone: "calm"})
    expect(nextStep(hybrid, undefined)).toEqual({text: "Waiting for 3 nominators", tone: "calm"})
    expect(nextStep(beatmap({status: BeatmapStatus.Reset, gamemodes: [mode(Osu, bn("1"), bn("2"))]}), undefined))
      .toEqual({text: "Reset. Waiting for User 1 and User 2", tone: "warn"})
    expect(nextStep(beatmap({status: BeatmapStatus.Nominated, gamemodes: [mode(Osu, [bn("1"), true], [bn("2"), true])]}), undefined))
      .toEqual({text: "Everyone nominated", tone: "calm"})
  })
})

describe("partOf", () => {
  it("leaves out beatmaps somebody is not part of", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1"))]})

    expect(partOf(map, undefined)).toBeUndefined()
    expect(partOf(map, "5")).toBeUndefined()
    expect(partOf({...map, status: BeatmapStatus.Ranked}, "1")).toBeUndefined()
    expect(partOf({...map, status: BeatmapStatus.Qualified}, "1")).toBe("qualified")
  })

  it("follows the nominations of the main mode", () => {
    expect(partOf(beatmap({gamemodes: [mode(Osu, bn("1"), [bn("2"), true])]}), "1")).toBe("move")
    expect(partOf(beatmap({gamemodes: [mode(Osu, bn("1"))]}), "1")).toBe("partner")
    expect(partOf(beatmap({gamemodes: [mode(Osu, bn("1"), bn("2"))]}), "1")).toBe("checking")
    expect(partOf(beatmap({gamemodes: [mode(Osu, [bn("1"), true], bn("2"))]}), "1")).toBe("waiting")
    expect(partOf(beatmap({status: BeatmapStatus.Disqualified, gamemodes: [mode(Osu, bn("1"), bn("2"))]}), "1")).toBe("move")
  })

  it("looks for a full nominator next to one on probation", () => {
    expect(partOf(beatmap({gamemodes: [mode(Osu, probation("1"), probation("2"))]}), "1")).toBe("partner")
  })

  it("lets another mode of a hybrid wait until the rest is nominated", () => {
    const waiting = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, bn("3", Taiko))]})
    const ready = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, [bn("1"), true], [bn("2"), true]), mode(Taiko, bn("3", Taiko))]})
    const done = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, [bn("3", Taiko), true])]})

    expect(partOf(waiting, "3")).toBe("checking")
    expect(partOf(ready, "3")).toBe("move")
    expect(partOf(done, "3")).toBe("waiting")
  })

  it("shows the most urgent group of all modes", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko, [user1Taiko(), false], [bn("4", Taiko), true])]})

    expect(partOf(map, "1")).toBe("move")
  })
})

function user1Taiko() {
  return {...bn("1"), gamemodes: [{gamemode: Osu, role: "Nominator" as const}, {gamemode: Taiko, role: "Nominator" as const}]}
}

describe("suggestions", () => {
  const me = bn("5")
  const rankedWith = (user: User) => user.osuId === "2" ? 3 : 0

  it("suggests beatmaps closest to being done first", () => {
    const empty = beatmap({osuId: "1"})
    const waiting = beatmap({osuId: "2", gamemodes: [mode(Osu, bn("2"))]})
    const nominated = beatmap({osuId: "3", gamemodes: [mode(Osu, [bn("3"), true])]})

    expect(suggestions([empty, waiting, nominated], me, rankedWith).map(it => [it.map.osuId, it.reason])).toEqual([
      ["3", "User 3 already nominated"],
      ["2", "User 2 is looking for a second nominator, you ranked 3 together"],
      ["1", "No nominators yet"]
    ])
  })

  it("prefers recent beatmaps", () => {
    const old = beatmap({osuId: "1", dateUpdated: daysAgo(30)})
    const recent = beatmap({osuId: "2", dateUpdated: daysAgo(1)})

    expect(suggestions([old, recent], me, rankedWith).map(it => it.map.osuId)).toEqual(["2", "1"])
  })

  it("leaves out what the user can't or already does", () => {
    const mine = beatmap({osuId: "1", gamemodes: [mode(Osu, me)]})
    const taiko = beatmap({osuId: "2", gamemodes: [mode(Taiko)]})
    const full = beatmap({osuId: "3", gamemodes: [mode(Osu, bn("1"), bn("2"))]})

    expect(suggestions([mine, taiko, full], me, rankedWith)).toEqual([])
  })

  it("asks for a full nominator next to one on probation", () => {
    const map = beatmap({gamemodes: [mode(Osu, probation("2"))]})

    expect(suggestions([map], me, rankedWith)[0].reason).toBe("User 2 is looking for a full nominator, you ranked 3 together")
    expect(suggestions([map], probation("6"), rankedWith)).toEqual([])
  })

  it("can be limited to one mode", () => {
    const hybrid = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu), mode(Taiko)]})
    const both = {...me, gamemodes: [...me.gamemodes, {gamemode: Taiko, role: "Nominator" as const}]}

    expect(suggestions([hybrid], both, rankedWith).map(it => it.mode)).toEqual([Osu])
    expect(suggestions([hybrid], both, rankedWith, Taiko).map(it => it.mode)).toEqual([Taiko])
  })
})

describe("dates", () => {
  it("reads timestamps in seconds and ISO dates", () => {
    expect(toDate(undefined)).toBeUndefined()
    expect(toDate(1_700_000_000)).toEqual(new Date(1_700_000_000_000))
    expect(toDate("2026-01-01T00:00:00Z")).toEqual(new Date("2026-01-01T00:00:00Z"))
    expect(toDate("not a date")).toBeUndefined()
    // Imported beatmaps use 1970 for dates they do not have
    expect(toDate(0)).toBeUndefined()
  })

  it("tells how long ago a beatmap was updated", () => {
    const label = (days: number) => ageLabel(beatmap({dateUpdated: daysAgo(days)}))

    expect(ageInDays(beatmap({dateUpdated: daysAgo(3)}))).toBe(3)
    expect(ageInDays(beatmap({dateUpdated: undefined}))).toBe(0)
    expect(ageInDays(beatmap({dateUpdated: daysAgo(-2)}))).toBe(0)
    expect([0, 13, 14, 59, 60, 364, 800].map(label)).toEqual(["today", "13d", "2w", "8w", "2mo", "12mo", "2y"])
    expect(ageLabel(beatmap({dateUpdated: undefined}))).toBe("")
  })

  it("calls a beatmap stale after three weeks, unless it is qualified", () => {
    expect(isStale(beatmap({dateUpdated: daysAgo(20)}))).toBe(false)
    expect(isStale(beatmap({dateUpdated: daysAgo(21)}))).toBe(true)
    expect(isStale(beatmap({status: BeatmapStatus.Qualified, dateUpdated: daysAgo(40)}))).toBe(false)
  })
})

describe("old beatmaps", () => {
  const old = (changes: Parameters<typeof beatmap>[0] = {}) => beatmap({dateUpdated: daysAgo(OLD_DAYS), ...changes})

  it("are those nobody changed for months", () => {
    expect(isOld(old())).toBe(true)
    expect(isOld(beatmap({dateUpdated: daysAgo(OLD_DAYS - 1)}))).toBe(false)
    // Qualified ones get ranked by themselves
    expect(isOld(old({status: BeatmapStatus.Qualified}))).toBe(false)
    // Without a date nothing is known
    expect(isOld(beatmap({dateUpdated: undefined}))).toBe(false)
  })

  it("stay for the people who map or nominate them", () => {
    const map = old({gamemodes: [mode(Osu, bn("1"))]})
    expect(hiddenAsOld(map, undefined)).toBe(true)
    expect(hiddenAsOld(map, "2")).toBe(true)
    expect(hiddenAsOld(map, "1")).toBe(false)
    expect(hiddenAsOld(map, map.mapper.osuId)).toBe(false)
    expect(hiddenAsOld(beatmap({dateUpdated: daysAgo(1)}), "2")).toBe(false)
  })
})

describe("lookingForNominator", () => {
  const nominated = beatmap({osuId: "1", gamemodes: [mode(Taiko, [bn("1", Taiko), true])]})
  const partnered = beatmap({osuId: "2", gamemodes: [mode(Osu, bn("2"))]})
  const empty = beatmap({osuId: "3", dateUpdated: daysAgo(40), gamemodes: [mode(Osu)]})
  const newer = beatmap({osuId: "4", dateUpdated: daysAgo(2), gamemodes: [mode(Osu), mode(Taiko)]})
  const full = beatmap({osuId: "5", gamemodes: [mode(Osu, bn("2"), bn("3"))]})
  const qualified = beatmap({osuId: "6", status: BeatmapStatus.Qualified, gamemodes: [mode(Osu)]})
  const maps = [newer, full, empty, partnered, qualified, nominated]

  it("lists every mode with those closest to done first", () => {
    expect(lookingForNominator(maps).map(it => [it.map.osuId, it.mode])).toEqual([["1", Taiko], ["2", Osu], ["3", Osu], ["4", Osu]])
    expect(lookingForNominator(maps)[0]).toMatchObject({partner: {osuId: "1"}, nominated: true, full: false})
  })

  it("lists one mode", () => {
    expect(lookingForNominator(maps, Taiko).map(it => [it.map.osuId, it.mode])).toEqual([["1", Taiko], ["4", Taiko]])
    expect(lookingForNominator(maps, Gamemode.Mania)).toEqual([])
  })
})
