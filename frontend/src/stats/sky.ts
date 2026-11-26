import {GAMEMODES} from "../Constants";
import {Gamemode} from "../models/Types";
import type {Link, network} from "./stats";

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
export function layout({people, links}: Network): Map<string, Point> {
  const most = Math.max(1, ...people.map(it => it.count))
  const strongest = Math.max(1, ...links.map(it => it.count))
  const index = new Map(people.map((it, position) => [it.user.osuId, position]))
  const spread = Math.max(1, Math.sqrt(people.length / 48))
  const homes = people.map(it => shares(it).reduce((sum, {mode, part}) =>
    ({x: sum.x + HOME[mode].x * spread * part, y: sum.y + HOME[mode].y * spread * part}), {x: 0, y: 0}))
  // Start around home on a sunflower, with the most active closest to it
  const points = people.map((_, position) => {
    const distance = 14 * Math.sqrt(position + .5), angle = position * 2.39996
    return {x: homes[position].x + distance * Math.cos(angle), y: homes[position].y + distance * Math.sin(angle)}
  })
  const sizes = people.map(it => radius(it.count, most))
  // Fewer rounds for a big sky, so it still appears at once
  const rounds = people.length > 150 ? 180 : 320

  for (let round = 0; round < rounds; round++) {
    const heat = 14 * (1 - round / rounds) + .2
    const forces = points.map((point, position) => ({x: (homes[position].x - point.x) * .06, y: (homes[position].y - point.y) * .06}))
    for (let a = 0; a < points.length; a++) {
      for (let b = a + 1; b < points.length; b++) {
        const dx = points[a].x - points[b].x, dy = points[a].y - points[b].y
        // Far away stars hardly push each other
        if (dx > 400 || dx < -400 || dy > 400 || dy < -400) continue
        const distance = Math.max(1, Math.hypot(dx, dy))
        const room = sizes[a] + sizes[b] + 34
        const push = 1500 / (distance * distance) + (distance < room ? (room - distance) * .4 : 0)
        forces[a].x += dx / distance * push
        forces[a].y += dy / distance * push
        forces[b].x -= dx / distance * push
        forces[b].y -= dy / distance * push
      }
    }
    links.forEach(link => {
      const a = index.get(link.a)!, b = index.get(link.b)!
      const dx = points[b].x - points[a].x, dy = points[b].y - points[a].y
      const distance = Math.max(1, Math.hypot(dx, dy))
      const pull = (distance - 80) * .02 * Math.sqrt(link.count / strongest)
      forces[a].x += dx / distance * pull
      forces[a].y += dy / distance * pull
      forces[b].x -= dx / distance * pull
      forces[b].y -= dy / distance * pull
    })
    points.forEach((point, position) => {
      const force = Math.max(.01, Math.hypot(forces[position].x, forces[position].y))
      const step = Math.min(force, heat)
      point.x += forces[position].x / force * step
      point.y += forces[position].y / force * step
    })
  }

  return new Map(people.map((it, position) => [it.user.osuId, points[position]]))
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
  const left = Math.min(...xs), right = Math.max(...xs), top = Math.min(...ys), bottom = Math.max(...ys)
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

/**
 * Names that fit without covering other names or stars, the most active first.
 * Zooming in makes room, so more names appear. The ones in `always` get theirs anyway.
 */
export function placeNames(people: Star[], screen: Map<string, Point>, sizes: Map<string, number>, size: Size, always: Set<string> = new Set()): Set<string> {
  const visible = people.filter(it => screen.has(it.user.osuId) && isVisible(screen.get(it.user.osuId)!, size, 0))
  const stars = visible.map(it => {
    const point = screen.get(it.user.osuId)!, r = sizes.get(it.user.osuId)!
    return {id: it.user.osuId, box: {left: point.x - r, right: point.x + r, top: point.y - r, bottom: point.y + r}}
  })
  const boxOf = (star: Star): Box => {
    const point = screen.get(star.user.osuId)!
    const half = star.user.username.length * 4.1 + 4, top = point.y + sizes.get(star.user.osuId)! + 3
    return {left: point.x - half, right: point.x + half, top, bottom: top + 17}
  }

  const placed: Box[] = []
  const named = new Set<string>()
  const first = [...visible.filter(it => always.has(it.user.osuId)), ...visible.filter(it => !always.has(it.user.osuId))]
  first.forEach(star => {
    const box = boxOf(star)
    const forced = always.has(star.user.osuId)
    const crossed = box.left < 0 || box.right > size.width || box.bottom > size.height
      || placed.some(it => overlaps(it, box))
      || stars.some(it => it.id !== star.user.osuId && overlaps(it.box, box))
    if (crossed && !forced) return
    placed.push(box)
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
