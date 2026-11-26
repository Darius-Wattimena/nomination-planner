import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode, probation, user} from "../test/builders";
import {
  canClaim,
  doneNominations,
  isHybrid,
  lacksFullNominator,
  mainModes,
  modesMissing,
  neededNominations,
  needsFullNominator,
  openSpot,
  openSpots,
  othersAreDone,
  roleOf,
  spots
} from "./nominations";

const {Osu, Taiko, Catch} = Gamemode

describe("a beatmap of one mode", () => {
  it("needs two nominations of the main mode", () => {
    const map = beatmap({gamemodes: [mode(Osu)]})

    expect(mainModes(map)).toEqual([Osu])
    expect(roleOf(map, map.gamemodes[0])).toBe("main")
    expect(isHybrid(map)).toBe(false)
    expect(neededNominations(map)).toBe(2)
    expect(openSpots(map)).toBe(2)
    expect(spots(map, map.gamemodes[0])).toHaveLength(2)
    expect(modesMissing(map)).toEqual(map.gamemodes)
  })

  it("counts what is nominated and what is still open", () => {
    const map = beatmap({gamemodes: [mode(Osu, [bn("1"), true], bn("2"))]})

    expect(doneNominations(map)).toBe(1)
    expect(openSpots(map)).toBe(0)
    expect(openSpot(map, map.gamemodes[0])).toBeUndefined()
    expect(modesMissing(map)).toEqual([])
  })
})

describe("a hybrid with a known main mode", () => {
  const map = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu), mode(Taiko)]})
  const [osu, taiko] = map.gamemodes

  it("needs two nominations of the main mode and one of every other mode", () => {
    expect(isHybrid(map)).toBe(true)
    expect(roleOf(map, osu)).toBe("main")
    expect(roleOf(map, taiko)).toBe("other")
    expect(neededNominations(map)).toBe(3)
    expect(openSpots(map)).toBe(3)
  })

  it("shows a single spot for every other mode", () => {
    expect(spots(map, taiko)).toEqual([{...taiko.nominators[0], index: 0}])
  })

  it("counts a single nomination for every other mode", () => {
    const nominated = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu), mode(Taiko, [bn("1", Taiko), true], [bn("2", Taiko), true])]})

    expect(doneNominations(nominated)).toBe(1)
  })

  it("is a hybrid when only another mode is on the planner", () => {
    const only = beatmap({mainGamemodes: [Taiko], gamemodes: [mode(Osu)]})

    expect(roleOf(only, only.gamemodes[0])).toBe("other")
    expect(isHybrid(only)).toBe(true)
    expect(neededNominations(only)).toBe(1)
  })

  it("knows when everything besides a mode is nominated", () => {
    const done = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu, [bn("1"), true], [bn("2"), true]), mode(Taiko, bn("3", Taiko))]})

    expect(othersAreDone(done, done.gamemodes[1])).toBe(true)
    expect(othersAreDone(done, done.gamemodes[0])).toBe(false)
  })
})

describe("a hybrid with an undecided main mode", () => {
  it("needs one nomination per mode and one more for the main mode", () => {
    const map = beatmap({gamemodes: [mode(Osu), mode(Taiko)]})

    expect(mainModes(map)).toEqual([Osu, Taiko])
    expect(roleOf(map, map.gamemodes[0])).toBe("undecided")
    expect(neededNominations(map)).toBe(3)
    expect(openSpots(map)).toBe(3)
    expect(spots(map, map.gamemodes[0]).map(it => it.optional)).toEqual([undefined, true])
  })

  it("stops offering a second spot once a mode has two nominators", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Taiko)]})

    expect(neededNominations(map)).toBe(3)
    expect(openSpots(map)).toBe(1)
    expect(spots(map, map.gamemodes[1])).toHaveLength(1)
  })

  it("takes the mode that is nominated first as the main mode", () => {
    const map = beatmap({gamemodes: [mode(Osu, bn("1")), mode(Taiko, [bn("2", Taiko), true])]})

    expect(mainModes(map)).toEqual([Taiko])
    expect(roleOf(map, map.gamemodes[0])).toBe("other")
    expect(roleOf(map, map.gamemodes[1])).toBe("main")
  })

  it("only considers the modes that can be the main one", () => {
    const map = beatmap({mainGamemodes: [Osu, Taiko], gamemodes: [mode(Osu), mode(Taiko), mode(Catch)]})

    expect(roleOf(map, map.gamemodes[2])).toBe("other")
    expect(neededNominations(map)).toBe(4)
  })
})

describe("probation", () => {
  it("needs a full nominator next to a nominator on probation", () => {
    const alone = beatmap({gamemodes: [mode(Osu, probation("1"))]})
    const both = beatmap({gamemodes: [mode(Osu, probation("1"), probation("2"))]})
    const mixed = beatmap({gamemodes: [mode(Osu, probation("1"), bn("2"))]})

    expect(lacksFullNominator(alone, alone.gamemodes[0])).toBe(false)
    expect(needsFullNominator(alone, alone.gamemodes[0])).toBe(true)
    expect(lacksFullNominator(both, both.gamemodes[0])).toBe(true)
    expect(needsFullNominator(both, both.gamemodes[0])).toBe(false)
    expect(lacksFullNominator(mixed, mixed.gamemodes[0])).toBe(false)
  })

  it("can't nominate another mode of a hybrid alone", () => {
    const map = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu), mode(Taiko, probation("1", Taiko))]})
    const empty = beatmap({mainGamemodes: [Osu], gamemodes: [mode(Osu), mode(Taiko)]})

    expect(lacksFullNominator(map, map.gamemodes[1])).toBe(true)
    expect(needsFullNominator(empty, empty.gamemodes[1])).toBe(true)
    expect(needsFullNominator(empty, empty.gamemodes[0])).toBe(false)
  })

  it("can wait for company while the main mode is undecided", () => {
    const map = beatmap({gamemodes: [mode(Osu, probation("1")), mode(Taiko)]})

    expect(lacksFullNominator(map, map.gamemodes[0])).toBe(false)
  })
})

describe("joining a mode", () => {
  const map = beatmap({gamemodes: [mode(Osu, bn("1"))]})
  const osu = map.gamemodes[0]

  it("is possible for a nominator of that mode", () => {
    expect(canClaim(map, osu, bn("5"))).toBe(true)
    expect(canClaim(map, osu, probation("5"))).toBe(true)
  })

  it("is not possible for others", () => {
    expect(canClaim(map, osu, undefined)).toBe(false)
    expect(canClaim(map, osu, user("5"))).toBe(false)
    expect(canClaim(map, osu, bn("5", Taiko))).toBe(false)
    expect(canClaim(map, osu, bn("1"))).toBe(false)
    expect(canClaim(beatmap({mapper: bn("5"), gamemodes: [osu]}), osu, bn("5"))).toBe(false)
    expect(canClaim({...map, status: BeatmapStatus.Ranked}, osu, bn("5"))).toBe(false)
    expect(canClaim({...map, status: BeatmapStatus.Graved}, osu, bn("5"))).toBe(false)
    const full = beatmap({gamemodes: [mode(Osu, bn("1"), bn("2"))]})
    expect(canClaim(full, full.gamemodes[0], bn("5"))).toBe(false)
  })

  it("needs a full nominator when the other one is on probation", () => {
    const waiting = beatmap({gamemodes: [mode(Osu, probation("1"))]})

    expect(canClaim(waiting, waiting.gamemodes[0], probation("5"))).toBe(false)
    expect(canClaim(waiting, waiting.gamemodes[0], bn("5"))).toBe(true)
  })
})
