import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {describeTurn, turnChanges} from "./turns";

const {Osu} = Gamemode
const me = bn("1")
const partner = bn("2")
const planned = beatmap({gamemodes: [mode(Osu, me, partner)]})

describe("turnChanges", () => {
  it("tells when the partner nominated, so it is your turn", () => {
    const nominated = {...planned, status: BeatmapStatus.Nominated, gamemodes: [mode(Osu, me, [partner, true])]}

    expect(turnChanges([planned], [nominated], "1")).toEqual([{kind: "turn", map: nominated}])
    // Not for the partner, who is waiting now
    expect(turnChanges([planned], [nominated], "2")).toEqual([])
  })

  it("tells when a beatmap you are on got disqualified, reset or qualified", () => {
    const nominated = {...planned, status: BeatmapStatus.Nominated, gamemodes: [mode(Osu, [me, true], [partner, true])]}
    const disqualified = {...planned, status: BeatmapStatus.Disqualified}
    const reset = {...planned, status: BeatmapStatus.Reset}
    const qualified = {...nominated, status: BeatmapStatus.Qualified}

    expect(turnChanges([nominated], [disqualified], "1")).toEqual([{kind: "disqualified", map: disqualified}])
    expect(turnChanges([nominated], [reset], "2")).toEqual([{kind: "reset", map: reset}])
    expect(turnChanges([nominated], [qualified], "1")).toEqual([{kind: "qualified", map: qualified}])
    // Only once
    expect(turnChanges([qualified], [{...qualified, note: "changed"}], "1")).toEqual([])
  })

  it("tells nothing about beatmaps you are not on", () => {
    const other = beatmap({osuId: "2", gamemodes: [mode(Osu, partner)]})

    expect(turnChanges([other], [{...other, status: BeatmapStatus.Disqualified}], "1")).toEqual([])
  })

  it("tells nothing the first time, for new beatmaps, or for what didn't change", () => {
    const nominated = {...planned, gamemodes: [mode(Osu, me, [partner, true])]}

    expect(turnChanges(undefined, [nominated], "1")).toEqual([])
    expect(turnChanges([planned], undefined, "1")).toEqual([])
    expect(turnChanges([planned], [nominated], undefined)).toEqual([])
    expect(turnChanges([], [nominated], "1")).toEqual([])
    expect(turnChanges([planned], [planned], "1")).toEqual([])
    expect(turnChanges([nominated], [{...nominated, note: "changed"}], "1")).toEqual([])
  })

  it("tells nothing when it became your turn without a new nomination", () => {
    const disqualified = {...planned, status: BeatmapStatus.Disqualified}

    expect(turnChanges([disqualified], [{...disqualified, status: BeatmapStatus.Reset}], "1")).toEqual([{kind: "reset", map: {...disqualified, status: BeatmapStatus.Reset}}])
    expect(turnChanges([{...planned, status: BeatmapStatus.Reset}], [{...planned, status: BeatmapStatus.Pending, note: "x"}], "1")).toEqual([])
  })
})

describe("describeTurn", () => {
  it("names the beatmap", () => {
    expect(describeTurn({kind: "turn", map: planned})).toEqual({title: "It's your turn", body: "Artist - Title"})
    expect(describeTurn({kind: "reset", map: planned}).title).toBe("Nomination reset")
  })
})
