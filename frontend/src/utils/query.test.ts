import {describe, expect, it} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, mode, probation, user} from "../test/builders";
import {filterMaps, formatQuery, modeParam, parseQuery} from "./query";
import {stageOf} from "./workflow";

const {Osu, Catch} = Gamemode

describe("parseQuery", () => {
  it("reads words and known filters", () => {
    expect(parseQuery('tv size MODE:catch is:open bn:"Some One" title:x')).toEqual([
      {value: "tv"},
      {value: "size"},
      {key: "mode", value: "catch"},
      {key: "is", value: "open"},
      {key: "bn", value: "Some One"},
      {value: "title:x"}
    ])
    expect(parseQuery("  ")).toEqual([])
    expect(parseQuery('"quoted words"')).toEqual([{value: "quoted words"}])
  })

  it("writes the same query back", () => {
    const query = 'tv mode:catch bn:"Some One" "two words" bn:""'

    expect(formatQuery(parseQuery(query))).toBe(query)
    expect(formatQuery([{key: "bn", value: 'say "hi"'}])).toBe('bn:"say hi"')
    expect(parseQuery('a"b')).toEqual([{value: 'a"b'}])
  })
})

describe("filterMaps", () => {
  const me = bn("5")
  const first = beatmap({osuId: "1", artist: "Camellia", title: "Exit This Earth", mapper: user("10", {}, "Sotarks"), note: "Check the kiai", gamemodes: [mode(Osu, me)]})
  const second = beatmap({osuId: "2", artist: "xi", title: "Blue Zenith", mapper: me, status: BeatmapStatus.Nominated, gamemodes: [mode(Catch, bn("6", Catch), [bn("7", Catch), true])]})
  const maps = [first, second]
  const ids = (query: string) => filterMaps(maps, query, me).map(it => it.osuId)

  it("finds words in the artist, title, mapper, id and note", () => {
    expect(ids("camellia")).toEqual(["1"])
    expect(ids("blue zenith")).toEqual(["2"])
    expect(ids("sotarks")).toEqual(["1"])
    expect(ids("2")).toEqual(["2"])
    expect(ids("kiai")).toEqual(["1"])
    expect(ids("")).toEqual(["1", "2"])
    expect(ids("nothing")).toEqual([])
  })

  it("filters on mode, status, stage, mapper and nominator", () => {
    expect(ids("mode:catch")).toEqual(["2"])
    expect(ids("mode:fruits")).toEqual(["2"])
    expect(ids("status:nominated")).toEqual(["2"])
    expect(ids("stage:open")).toEqual(["1"])
    expect(ids("by:me")).toEqual(["2"])
    expect(ids("by:sotarks")).toEqual(["1"])
    expect(ids("by:10")).toEqual(["1"])
    expect(ids("bn:me")).toEqual(["1"])
    expect(ids("bn:user 7")).toEqual([])
    expect(ids('bn:"user 7"')).toEqual(["2"])
    expect(ids("bn:7")).toEqual(["2"])
  })

  it("never finds a missing nominator", () => {
    expect(ids("bn:none")).toEqual([])
    expect(ids("bn:0")).toEqual([])
  })

  it("filters on what has to happen", () => {
    expect(ids("is:open")).toEqual(["1"])
    expect(ids("is:mine")).toEqual(["1"])
    expect(ids("is:move")).toEqual([])
    expect(ids("is:noted")).toEqual(["1"])
    expect(ids("is:stale")).toEqual(["1", "2"])
    expect(ids("is:unknown")).toEqual(["1", "2"])
  })

  it("takes values of one filter as alternatives and needs every filter", () => {
    expect(ids("mode:osu mode:catch")).toEqual(["1", "2"])
    expect(ids("mode:osu status:nominated")).toEqual([])
    expect(ids("is:open is:noted")).toEqual(["1"])
    expect(ids("is:open is:mine is:noted camellia")).toEqual(["1"])
  })

  it("needs a nominator like the column of the board", () => {
    const nominated = beatmap({osuId: "3", status: BeatmapStatus.Nominated, gamemodes: [mode(Osu, [bn("1"), true])]})
    const disqualified = beatmap({osuId: "4", status: BeatmapStatus.Disqualified})
    const onProbation = beatmap({osuId: "5", gamemodes: [mode(Osu, probation("1"), probation("2"))]})
    const planned = beatmap({osuId: "6", gamemodes: [mode(Osu, bn("1"), bn("2"))]})
    const all = [first, nominated, disqualified, onProbation, planned]

    expect(filterMaps(all, "is:open", me).map(it => it.osuId)).toEqual(["1", "5"])
    expect(filterMaps(all, "is:open", me)).toEqual(all.filter(it => stageOf(it) === "open"))
    expect(filterMaps(all, "mode:osu is:open", me).map(it => it.osuId)).toEqual(["1", "5"])
  })

  it("needs the open spot in the mode that is filtered on", () => {
    const hybrid = beatmap({osuId: "3", mainGamemodes: [Osu], gamemodes: [mode(Osu, bn("1"), bn("2")), mode(Catch)]})

    expect(filterMaps([hybrid], "mode:osu is:open", me)).toEqual([])
    expect(filterMaps([hybrid], "mode:catch is:open", me)).toEqual([hybrid])
    expect(filterMaps([hybrid], "mode:osu IS:Open", me)).toEqual([])
  })
})

describe("modeParam", () => {
  it("reads a mode from the address", () => {
    expect(modeParam("fruits")).toBe(Catch)
    expect(modeParam("all")).toBe("all")
    expect(modeParam("catch")).toBeUndefined()
    expect(modeParam(null)).toBeUndefined()
  })
})
