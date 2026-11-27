import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {network} from "./stats";
import type {Camera, Size} from "./sky";
import {clampZoom, findStars, firstTogether, fit, isVisible, layout, MAX_ZOOM, MIN_ZOOM, pan, partnersOf, placeNames, starScale, steps, strongestLinks, timeline, toScreen, toSky, upTo, zoomAt, newPairs, pairKey} from "./sky";
import {resolve} from "./range";

const {Osu, Mania} = Gamemode
const SIZE: Size = {width: 800, height: 500}

const sky = network([
  beatmap({osuId: "1", gamemodes: [mode(Osu, bn("1"), bn("2"))]}),
  beatmap({osuId: "2", gamemodes: [mode(Osu, bn("1"), bn("2"))]}),
  beatmap({osuId: "3", gamemodes: [mode(Osu, bn("1"), bn("3"))]}),
  beatmap({osuId: "4", gamemodes: [mode(Mania, bn("4", Mania), bn("5", Mania))]})
], undefined)

describe("network", () => {
  it("has everyone, the most active first", () => {
    expect(sky.people.map(it => it.user.osuId)).toEqual(["1", "2", "3", "4", "5"])
    expect(sky.links.map(it => [it.a, it.b, it.count])).toEqual([["1", "2", 2], ["1", "3", 1], ["4", "5", 1]])
  })
})

describe("layout", () => {
  it("places everyone, the same every time", () => {
    const points = layout(sky)
    expect(Array.from(points.keys())).toEqual(["1", "2", "3", "4", "5"])
    expect(layout(sky)).toEqual(points)
  })

  it("keeps the modes apart", () => {
    const points = layout(sky)
    // osu! is on the left, osu!mania on the right
    expect(points.get("1")!.x).toBeLessThan(points.get("4")!.x)
  })
})

describe("camera", () => {
  const camera: Camera = {x: 100, y: 50, zoom: 2}

  it("turns points of the sky into pixels and back", () => {
    expect(toScreen(camera, SIZE, {x: 100, y: 50})).toEqual({x: 400, y: 250})
    expect(toScreen(camera, SIZE, {x: 110, y: 40})).toEqual({x: 420, y: 230})
    expect(toSky(camera, SIZE, {x: 420, y: 230})).toEqual({x: 110, y: 40})
  })

  it("zooms around the pointer", () => {
    const zoomed = zoomAt(camera, SIZE, 2, {x: 600, y: 100})
    expect(zoomed.zoom).toBe(4)
    expect(toSky(zoomed, SIZE, {x: 600, y: 100})).toEqual(toSky(camera, SIZE, {x: 600, y: 100}))
  })

  it("only zooms so far", () => {
    expect(zoomAt(camera, SIZE, 100, {x: 0, y: 0}).zoom).toBe(MAX_ZOOM)
    expect(zoomAt(camera, SIZE, .0001, {x: 0, y: 0}).zoom).toBe(MIN_ZOOM)
    expect(clampZoom(1)).toBe(1)
  })

  it("moves with the pointer", () => {
    expect(pan(camera, 20, -10)).toEqual({x: 90, y: 55, zoom: 2})
  })

  it("fits points in view", () => {
    const fitted = fit([{x: -100, y: -50}, {x: 100, y: 50}], SIZE)
    expect(fitted.x).toBe(0)
    expect(fitted.zoom).toBeCloseTo(Math.min(704 / 200, 430 / 100, 1.4))
    const corner = toScreen(fitted, SIZE, {x: 100, y: 50})
    expect(isVisible(corner, SIZE, 0)).toBe(true)
    // A single point is not zoomed in endlessly
    expect(fit([{x: 5, y: 5}], SIZE).zoom).toBe(1.4)
    expect(fit([], SIZE)).toEqual({x: 0, y: 0, zoom: 1})
  })

  it("shrinks stars a bit when zooming out", () => {
    expect(starScale(2)).toBe(1)
    expect(starScale(.25)).toBe(.5)
    expect(starScale(.01)).toBe(.35)
  })

  it("knows what is in view", () => {
    expect(isVisible({x: -10, y: 10}, SIZE)).toBe(true)
    expect(isVisible({x: -10, y: 10}, SIZE, 0)).toBe(false)
    expect(isVisible({x: 10, y: 600}, SIZE)).toBe(false)
  })
})

describe("names", () => {
  const people = sky.people.slice(0, 3)
  const sizes = new Map(people.map(it => [it.user.osuId, 10]))

  it("are placed when they fit, the most active first", () => {
    const screen = new Map([["1", {x: 100, y: 100}], ["2", {x: 300, y: 100}], ["3", {x: 140, y: 105}]])
    // The names of 1 and 3 would cover each other
    expect(placeNames(people, screen, sizes, SIZE)).toEqual(new Set(["1", "2"]))
    expect(placeNames(people, screen, sizes, SIZE, new Set(["3"]))).toEqual(new Set(["3", "2"]))
  })

  it("leave out who is not in view or would not fit", () => {
    const screen = new Map([["1", {x: 100, y: 495}], ["2", {x: 900, y: 100}], ["3", {x: 3, y: 100}]])
    expect(placeNames(people, screen, sizes, SIZE)).toEqual(new Set())
    expect(placeNames(people, new Map(), sizes, SIZE)).toEqual(new Set())
  })
})

describe("partners and pairs", () => {
  it("lists who someone ranked with, most first", () => {
    expect(partnersOf(sky.links, "1").map(it => [it.id, it.link.count])).toEqual([["2", 2], ["3", 1]])
    expect(partnersOf(sky.links, "3").map(it => it.id)).toEqual(["1"])
    expect(partnersOf(sky.links, "6")).toEqual([])
  })

  it("draws the strongest lines", () => {
    const links = Array.from({length: 300}, (_, index) => ({...sky.links[0], count: 300 - index}))
    expect(strongestLinks(links, 10)).toHaveLength(60)
    expect(strongestLinks(links, 100)).toHaveLength(200)
  })
})

describe("finding someone", () => {
  const people = network([
    beatmap({osuId: "1", gamemodes: [mode(Osu, bn("1", Osu), bn("2"))]}),
    beatmap({osuId: "2", gamemodes: [mode(Osu, bn("2"), bn("3"))]})
  ].map(it => ({...it, gamemodes: it.gamemodes.map(own => ({...own, nominators: own.nominators.map(slot => ({...slot, nominator: {...slot.nominator, username: {"1": "Mira", "2": "Amir", "3": "Rim"}[slot.nominator.osuId] ?? slot.nominator.username}}))}))})), undefined).people

  it("puts names starting with the text first, then the most active", () => {
    expect(findStars(people, " mi").map(it => it.user.username)).toEqual(["Mira", "Amir"])
    expect(findStars(people, "R").map(it => it.user.username)).toEqual(["Rim", "Amir", "Mira"])
    expect(findStars(people, "r", 1).map(it => it.user.username)).toEqual(["Rim"])
  })

  it("finds nobody without text", () => {
    expect(findStars(people, " ")).toEqual([])
    expect(findStars(people, "zzz")).toEqual([])
  })
})

describe("new pairs and time", () => {
  const at = (osuId: string, dateRanked: string, ...gamemodes: ReturnType<typeof mode>[]) => beatmap({osuId, dateRanked, gamemodes})
  const maps = [
    at("1", "2025-06-01T12:00:00", mode(Osu, bn("1"), bn("2"))),
    at("2", "2026-02-10T12:00:00", mode(Osu, bn("1"), bn("2"))),
    at("3", "2026-03-05T12:00:00", mode(Osu, bn("1"), bn("3"))),
    // On two modes, still once
    at("4", "2026-04-20T12:00:00", mode(Osu, bn("3"), bn("4")), mode(Mania, bn("3", Mania), bn("4", Mania)))
  ]
  const range = resolve("2026-01-01..2026-06-30")
  const scope = maps.slice(1)

  it("knows when a pair first ranked together, over all time", () => {
    const first = firstTogether(maps, undefined)
    expect(first.get(pairKey("2", "1"))).toBe(new Date("2025-06-01T12:00:00").getTime())
    expect(first.get("3-4")).toBe(new Date("2026-04-20T12:00:00").getTime())
    expect(Array.from(newPairs(network(scope, undefined).links, first, range)).sort()).toEqual(["1-3", "3-4"])
  })

  it("counts what was ranked by every step", () => {
    const times = timeline(scope, undefined)
    expect(times.links.get("3-4")).toHaveLength(1)
    const list = steps(range)
    expect(list.map(it => it.short)).toEqual(["Jan", "Feb", "Mar", "Apr", "May", "Jun"])
    const people = (step: number) => ["1", "2", "3", "4"].filter(id => upTo(times.people.get(id), list[step].end.getTime()) > 0)
    expect(people(0)).toEqual([])
    expect(people(1)).toEqual(["1", "2"])
    expect(people(2)).toEqual(["1", "2", "3"])
    expect(people(5)).toEqual(["1", "2", "3", "4"])
    expect(upTo(times.people.get("1"), list[5].end.getTime())).toBe(2)
    expect(upTo(undefined, 1)).toBe(0)
  })

  it("steps by week for less than about 3 months", () => {
    expect(steps(resolve("2026-03-02..2026-03-29")).map(it => it.short)).toEqual(["2 Mar", "9 Mar", "16 Mar", "23 Mar"])
  })
})
