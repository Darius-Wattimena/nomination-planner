import {GAMEMODES} from "../Constants";
import type {Beatmap} from "../models/Types";
import {Gamemode} from "../models/Types";
import type {Bucket, TimeRange} from "./range";
import {buckets} from "./range";
import type {Link, network} from "./stats";
import {nominatorsOf, pairsOf, rankedDate} from "./stats";
import {displayName, isMissing} from "../utils/users";

export type Network = ReturnType<typeof network>
export type Star = Network["people"][number]

export interface Point {
  x: number
  y: number
}

export interface Size {
  width: number
  height: number
}

/** What part of the sky is shown: the point in the middle, and how many pixels one unit of the sky takes */
export interface Camera {
  x: number
  y: number
  zoom: number
}

/** Every mode has its own part of the sky */
const HOME: Record<Gamemode, Point> = {
  [Gamemode.Osu]: {x: -230, y: -120},
  [Gamemode.Taiko]: {x: 230, y: -120},
  [Gamemode.Catch]: {x: -230, y: 120},
  [Gamemode.Mania]: {x: 230, y: 120}
}

export const MIN_ZOOM = .05
export const MAX_ZOOM = 4

/** Radius in pixels when zoomed in, bigger is more ranked */
export const radius = (count: number, most: number) => 6 + 13 * Math.sqrt(count / most)

/** Stars shrink a bit when zooming out, or the sky would be one big blob */
export const starScale = (zoom: number) => Math.min(1, Math.max(.35, Math.sqrt(zoom)))

/** The modes of a person, as parts of one */
export function shares({modes}: Star): {mode: Gamemode, part: number}[] {
  const total = GAMEMODES.reduce((sum, mode) => sum + (modes[mode] ?? 0), 0)
  return GAMEMODES.filter(mode => modes[mode]).map(mode => ({mode, part: modes[mode]! / total}))
}

export function mainMode(star: Star) {
  return [...shares(star)].sort((a, b) => b.part - a.part)[0].mode
}

/**
 * People are drawn to their modes, so who nominates several ends up in between, and to those they ranked with.
 * The more people, the bigger the sky. Always gives the same result.
 */
/** The largest, without spreading them as arguments, which fails for tens of thousands */
export function largest(values: number[], least = -Infinity): number {
  let most = least
  for (const value of values) if (value > most) most = value
  return most
}

/** Stars further apart than this hardly push each other */
const REACH = 400
/** Squares of the sky, larger than the room two of the largest stars need, so whoever is closer is in a neighbouring one */
const CLOSE = 80
const keyOf = (column: number, row: number) => (column + 50_000) * 100_000 + row + 50_000
const squareOf = (x: number, y: number) => keyOf(Math.floor(x / CLOSE), Math.floor(y / CLOSE))

export function layout({people, links}: Network): Map<string, Point> {
  const count = people.length
  const most = largest(people.map(it => it.count), 1)
  const strongest = largest(links.map(it => it.count), 1)
  const index = new Map(people.map((it, position) => [it.user.osuId, position]))
  const spread = Math.max(1, Math.sqrt(count / 48))
  const homes = people.map(it => shares(it).reduce((sum, {mode, part}) =>
    ({x: sum.x + HOME[mode].x * spread * part, y: sum.y + HOME[mode].y * spread * part}), {x: 0, y: 0}))
  // Numbers in arrays rather than objects, as a big sky goes through them millions of times
  const xs = new Float64Array(count), ys = new Float64Array(count)
  const fx = new Float64Array(count), fy = new Float64Array(count)
  // Start around home on a sunflower, with the most active closest to it
  homes.forEach((home, position) => {
    const distance = 14 * Math.sqrt(position + .5), angle = position * 2.39996
    xs[position] = home.x + distance * Math.cos(angle)
    ys[position] = home.y + distance * Math.sin(angle)
  })
  const sizes = Float64Array.from(people, it => radius(it.count, most))
  const linkA = Int32Array.from(links, it => index.get(it.a)!), linkB = Int32Array.from(links, it => index.get(it.b)!)
  const linkPull = Float64Array.from(links, it => .02 * Math.sqrt(it.count / strongest))
  // Fewer rounds for a big sky, so it still appears at once
  const rounds = count > 150 ? 180 : 320

  for (let round = 0; round < rounds; round++) {
    const heat = 14 * (1 - round / rounds) + .2
    for (let a = 0; a < count; a++) {
      fx[a] = (homes[a].x - xs[a]) * .06
      fy[a] = (homes[a].y - ys[a]) * .06
    }

    // Stars close by push each other one by one, those further away together by the square they are in. Exactly would mean
    // every pair of a big sky, which takes seconds for the whole history.
    const squares = new Map<number, number[]>()
    for (let a = 0; a < count; a++) {
      const key = squareOf(xs[a], ys[a])
      const square = squares.get(key)
      if (square) square.push(a)
      else squares.set(key, [a])
    }
    const centres = Array.from(squares.values(), members => {
      let x = 0, y = 0
      for (const it of members) {
        x += xs[it]
        y += ys[it]
      }
      return {column: Math.floor(xs[members[0]] / CLOSE), row: Math.floor(ys[members[0]] / CLOSE), x: x / members.length, y: y / members.length, count: members.length}
    })

    for (let a = 0; a < count; a++) {
      const column = Math.floor(xs[a] / CLOSE), row = Math.floor(ys[a] / CLOSE)
      for (let x = column - 1; x <= column + 1; x++) {
        for (let y = row - 1; y <= row + 1; y++) {
          const members = squares.get(keyOf(x, y))
          if (!members) continue
          for (const b of members) {
            if (b <= a) continue
            const dx = xs[a] - xs[b], dy = ys[a] - ys[b]
            const distance = Math.max(1, Math.sqrt(dx * dx + dy * dy))
            const room = sizes[a] + sizes[b] + 34
            const push = (1500 / (distance * distance) + (distance < room ? (room - distance) * .4 : 0)) / distance
            fx[a] += dx * push
            fy[a] += dy * push
            fx[b] -= dx * push
            fy[b] -= dy * push
          }
        }
      }
      for (const centre of centres) {
        const dx = xs[a] - centre.x, dy = ys[a] - centre.y
        const near = Math.abs(centre.column - column) <= 1 && Math.abs(centre.row - row) <= 1
        if (near || dx > REACH || dx < -REACH || dy > REACH || dy < -REACH) continue
        const distance = Math.max(1, Math.sqrt(dx * dx + dy * dy))
        const push = 1500 * centre.count / (distance * distance * distance)
        fx[a] += dx * push
        fy[a] += dy * push
      }
    }

    for (let link = 0; link < linkA.length; link++) {
      const a = linkA[link], b = linkB[link]
      const dx = xs[b] - xs[a], dy = ys[b] - ys[a]
      const distance = Math.max(1, Math.sqrt(dx * dx + dy * dy))
      const pull = (distance - 80) * linkPull[link] / distance
      fx[a] += dx * pull
      fy[a] += dy * pull
      fx[b] -= dx * pull
      fy[b] -= dy * pull
    }
    for (let a = 0; a < count; a++) {
      const force = Math.max(.01, Math.sqrt(fx[a] * fx[a] + fy[a] * fy[a]))
      const step = Math.min(force, heat)
      xs[a] += fx[a] / force * step
      ys[a] += fy[a] / force * step
    }
  }

  return new Map(people.map((it, position) => [it.user.osuId, {x: xs[position], y: ys[position]}]))
}

export const clampZoom = (zoom: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))

export function toScreen(camera: Camera, size: Size, point: Point): Point {
  return {x: size.width / 2 + (point.x - camera.x) * camera.zoom, y: size.height / 2 + (point.y - camera.y) * camera.zoom}
}

export function toSky(camera: Camera, size: Size, point: Point): Point {
  return {x: camera.x + (point.x - size.width / 2) / camera.zoom, y: camera.y + (point.y - size.height / 2) / camera.zoom}
}

/** Shows all of these points, with room for their names at the edges */
export function fit(points: Point[], size: Size, most = 1.4): Camera {
  if (points.length === 0) return {x: 0, y: 0, zoom: 1}
  const xs = points.map(it => it.x), ys = points.map(it => it.y)
  const left = -largest(xs.map(it => -it)), right = largest(xs), top = -largest(ys.map(it => -it)), bottom = largest(ys)
  // Room for names at the edges, less on a small screen
  const width = size.width - Math.min(120, size.width * .12), height = size.height - Math.min(90, size.height * .14)
  const zoom = Math.min(width / Math.max(1, right - left), height / Math.max(1, bottom - top), most)
  // A little higher, as names are below the stars
  return {x: (left + right) / 2, y: (top + bottom) / 2 + 8 / zoom, zoom: clampZoom(zoom)}
}

/** Zoom in or out by a factor, keeping the point under the pointer where it is */
export function zoomAt(camera: Camera, size: Size, factor: number, at: Point): Camera {
  const zoom = clampZoom(camera.zoom * factor)
  const sky = toSky(camera, size, at)
  return {zoom, x: sky.x - (at.x - size.width / 2) / zoom, y: sky.y - (at.y - size.height / 2) / zoom}
}

/** Move by a distance in pixels */
export function pan(camera: Camera, dx: number, dy: number): Camera {
  return {...camera, x: camera.x - dx / camera.zoom, y: camera.y - dy / camera.zoom}
}

export function isVisible(point: Point, size: Size, margin = 40) {
  return point.x > -margin && point.x < size.width + margin && point.y > -margin && point.y < size.height + margin
}

interface Box {
  left: number
  right: number
  top: number
  bottom: number
}

const overlaps = (a: Box, b: Box) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

/** Boxes on the screen by the squares they cover, so checking one only looks at those nearby */
class Boxes {
  private readonly squares = new Map<string, Box[]>()

  private keys(box: Box) {
    const keys: string[] = []
    for (let x = Math.floor(box.left / SQUARE); x <= Math.floor(box.right / SQUARE); x++)
      for (let y = Math.floor(box.top / SQUARE); y <= Math.floor(box.bottom / SQUARE); y++) keys.push(`${x},${y}`)
    return keys
  }

  add(box: Box) {
    this.keys(box).forEach(key => {
      const square = this.squares.get(key)
      if (square) square.push(box)
      else this.squares.set(key, [box])
    })
  }

  overlaps(box: Box, except?: Box) {
    return this.keys(box).some(key => this.squares.get(key)?.some(it => it !== except && overlaps(it, box)))
  }
}

const SQUARE = 64

/**
 * Names that fit without covering other names or stars, the most active first.
 * Zooming in makes room, so more names appear. The ones in `always` get theirs anyway.
 */
export function placeNames(people: Star[], screen: Map<string, Point>, sizes: Map<string, number>, size: Size, always: Set<string> = new Set()): Set<string> {
  const visible = people.filter(it => screen.has(it.user.osuId) && isVisible(screen.get(it.user.osuId)!, size, 0))
  const stars = new Boxes()
  const starBoxes = new Map(visible.map(it => {
    const point = screen.get(it.user.osuId)!, r = sizes.get(it.user.osuId)!
    const box = {left: point.x - r, right: point.x + r, top: point.y - r, bottom: point.y + r}
    stars.add(box)
    return [it.user.osuId, box]
  }))
  const boxOf = (star: Star): Box => {
    const point = screen.get(star.user.osuId)!
    const half = displayName(star.user).length * 4.1 + 4, top = point.y + sizes.get(star.user.osuId)! + 3
    return {left: point.x - half, right: point.x + half, top, bottom: top + 17}
  }

  const placed = new Boxes()
  const named = new Set<string>()
  const first = [...visible.filter(it => always.has(it.user.osuId)), ...visible.filter(it => !always.has(it.user.osuId))]
  first.forEach(star => {
    const box = boxOf(star)
    const forced = always.has(star.user.osuId)
    const crossed = box.left < 0 || box.right > size.width || box.bottom > size.height
      || placed.overlaps(box)
      || stars.overlaps(box, starBoxes.get(star.user.osuId))
    if (crossed && !forced) return
    placed.add(box)
    named.add(star.user.osuId)
  })
  return named
}

/** Everyone a person ranked with, most first */
export function partnersOf(links: Link[], id: string) {
  return links
    .filter(it => it.a === id || it.b === id)
    .map(it => ({id: it.a === id ? it.b : it.a, link: it}))
    .sort((a, b) => b.link.count - a.link.count)
}

/** Lines drawn for this many people: the strongest ones, as nearly everyone worked together once */
export function strongestLinks(links: Link[], people: number) {
  return links.slice(0, Math.max(60, people * 2))
}

/** People whose name contains the text, those starting with it first, then the most active */
export function findStars(people: Star[], text: string, limit = 6) {
  const search = text.trim().toLowerCase()
  if (!search) return []
  return people
    .filter(it => it.user.username.toLowerCase().includes(search))
    .sort((a, b) => Number(b.user.username.toLowerCase().startsWith(search)) - Number(a.user.username.toLowerCase().startsWith(search)) || b.count - a.count)
    .slice(0, limit)
}

/** The key of a pair, the same either way round, as in the network */
export const pairKey = (a: string, b: string) => a < b ? `${a}-${b}` : `${b}-${a}`

/** The pairs on a beatmap the way the network counts them: every two nominators of the same mode, once per beatmap */
function pairsOn(map: Beatmap, only: Gamemode | undefined) {
  const keys = new Set<string>()
  map.gamemodes.filter(it => !only || it.gamemode === only).forEach(({nominators}) => {
    const ids = Array.from(new Set(nominators.map(it => it.nominator).filter(it => !isMissing(it)).map(it => it.osuId)))
    pairsOf(ids).forEach(([a, b]) => keys.add(pairKey(a, b)))
  })
  return keys
}

/** When every pair ranked their first beatmap together, over everything given */
export function firstTogether(maps: Beatmap[], only: Gamemode | undefined): Map<string, number> {
  const first = new Map<string, number>()
  maps.forEach(map => {
    const time = rankedDate(map)?.getTime()
    if (time === undefined) return
    pairsOn(map, only).forEach(key => {
      const known = first.get(key)
      if (known === undefined || time < known) first.set(key, time)
    })
  })
  return first
}

/** The pairs whose first beatmap together was ranked in the range */
export function newPairs(links: Link[], first: Map<string, number>, range: {from: Date, to: Date}): Set<string> {
  return new Set(links.map(it => pairKey(it.a, it.b)).filter(key => {
    const time = first.get(key)
    return time !== undefined && time >= range.from.getTime() && time < range.to.getTime()
  }))
}

export interface Timeline {
  /** When each person ranked something, in order */
  people: Map<string, number[]>
  /** When each pair ranked something together, in order */
  links: Map<string, number[]>
}

/** Every moment someone or a pair ranked something, to show the sky as it was at any time */
export function timeline(maps: Beatmap[], only: Gamemode | undefined): Timeline {
  const people = new Map<string, number[]>(), links = new Map<string, number[]>()
  const push = (all: Map<string, number[]>, key: string, time: number) => {
    const known = all.get(key)
    if (known) known.push(time)
    else all.set(key, [time])
  }
  maps.forEach(map => {
    const time = rankedDate(map)?.getTime()
    if (time === undefined) return
    nominatorsOf(map, only).forEach(user => push(people, user.osuId, time))
    pairsOn(map, only).forEach(key => push(links, key, time))
  })
  const sort = (all: Map<string, number[]>) => all.forEach(times => times.sort((a, b) => a - b))
  sort(people)
  sort(links)
  return {people, links}
}

/** How many of these moments, in order, come before the end */
export function upTo(times: number[] | undefined, end: number) {
  if (!times) return 0
  let low = 0, high = times.length
  while (low < high) {
    const middle = (low + high) >> 1
    if (times[middle] < end) low = middle + 1
    else high = middle
  }
  return low
}

/** The steps to play the range through: months from about 3 months, weeks for less */
export function steps(range: TimeRange, now = new Date()): Bucket[] {
  const days = (range.to.getTime() - range.from.getTime()) / 86_400_000
  return buckets(range, days >= 89 ? "month" : "week", now)
}
