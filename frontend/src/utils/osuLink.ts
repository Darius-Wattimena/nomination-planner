import type {Gamemode} from "../models/Types";

const LINK = /^(?:https?:\/\/)?(?:osu|old)\.ppy\.sh\/(?:beatmapsets|s)\/(\d+)(?:\S*#(osu|taiko|fruits|mania)\b)?\S*$/

export function parseOsuLink(text: string): {id: string, mode?: Gamemode} | undefined {
  const match = text.trim().match(LINK)
  return match ? {id: match[1], mode: match[2] as Gamemode | undefined} : undefined
}

/** A link or the bare ID of a beatmap set */
export function parseBeatmap(text: string): {id: string, mode?: Gamemode} | undefined {
  const id = text.trim()
  return /^[0-9]{2,}$/.test(id) ? {id, mode: undefined} : parseOsuLink(id)
}

export const beatmapUrl = (id: string) => `https://osu.ppy.sh/beatmapsets/${id}`

/** Where nominating happens */
export const discussionUrl = (id: string) => `${beatmapUrl(id)}/discussion`
