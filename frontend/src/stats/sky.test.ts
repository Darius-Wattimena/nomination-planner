import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {beatmap, bn, mode} from "../test/builders";
import {network} from "./stats";
import type {Camera, Size} from "./sky";
import {clampZoom, findStars, fit, isVisible, layout, MAX_ZOOM, MIN_ZOOM, pan, partnersOf, placeNames, starScale, strongestLinks, toScreen, toSky, zoomAt} from "./sky";

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
