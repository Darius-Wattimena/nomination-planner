import type {Gamemode} from "../models/Types";

const LINK = /^(?:https?:\/\/)?(?:osu|old)\.ppy\.sh\/(?:beatmapsets|s)\/(\d+)(?:\S*#(osu|taiko|fruits|mania)\b)?\S*$/

export function parseOsuLink(text: string): {id: string, mode?: Gamemode} | undefined {
  const match = text.trim().match(LINK)
  return match ? {id: match[1], mode: match[2] as Gamemode | undefined} : undefined
}
