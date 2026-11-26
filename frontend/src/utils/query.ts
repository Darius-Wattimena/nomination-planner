import type {Beatmap, User} from "../models/Types";
import {Gamemode} from "../models/Types";
import {openSpot, openSpots} from "./nominations";
import {isMissing} from "./users";
import {partOf, isStale, stageOf} from "./workflow";

/** A query is plain text, so it can be typed, shared and saved: "tv size mode:catch is:open" */
export interface Token {
  key?: string
  value: string
}

export const MODE_WORDS: Record<Gamemode, string> = {
  [Gamemode.Osu]: "osu",
  [Gamemode.Taiko]: "taiko",
  [Gamemode.Catch]: "catch",
  [Gamemode.Mania]: "mania"
}

export function parseQuery(text: string): Token[] {
  const tokens: Token[] = []
  for (const match of Array.from(text.matchAll(/(?:([a-z]+):)?(?:"([^"]*)"|(\S+))/gi))) {
    const value = match[2] ?? match[3] ?? ""
    const key = match[1]?.toLowerCase()
    if (key && ["mode", "stage", "status", "is", "bn", "by"].includes(key)) {
      tokens.push({key, value})
    } else {
      tokens.push({value: key ? `${match[1]}:${value}` : value})
    }
  }
  return tokens
}

export function formatQuery(tokens: Token[]) {
  return tokens.map(({key, value}) => {
    // A quoted value can't hold quotes itself
    const text = value === "" || /\s/.test(value) ? `"${value.replaceAll('"', "")}"` : value
    return key ? `${key}:${text}` : text
  }).join(" ")
}

function matches(map: Beatmap, {key, value}: Token, user: User | undefined): boolean {
  const text = value.toLowerCase()
  switch (key) {
    case "mode":
      return map.gamemodes.some(it => MODE_WORDS[it.gamemode] === text || it.gamemode === text)
    case "stage":
      return stageOf(map) === text
    case "status":
      return map.status.toLowerCase() === text
    case "by":
      return text === "me" ? map.mapper.osuId === user?.osuId : map.mapper.osuId === text || map.mapper.username.toLowerCase().includes(text)
    case "bn":
      return map.gamemodes.some(mode => mode.nominators.some(({nominator}) => !isMissing(nominator)
        && (text === "me" ? nominator.osuId === user?.osuId : nominator.osuId === text || nominator.username.toLowerCase().includes(text))))
    case "is":
      if (text === "open") return openSpots(map) > 0
      if (text === "mine") return partOf(map, user?.osuId) !== undefined
      if (text === "move") return partOf(map, user?.osuId) === "move"
      if (text === "stale") return isStale(map)
      if (text === "noted") return !!map.note?.trim()
      return true
    default:
      return `${map.artist} ${map.title} ${map.mapper.username} ${map.osuId} ${map.note ?? ""}`.toLowerCase().includes(text)
  }
}

/** Values of the same kind are alternatives, everything else has to match */
export function filterMaps(maps: Beatmap[], text: string, user: User | undefined) {
  const tokens = parseQuery(text)
  const groups = new Map<string, Token[]>()
  tokens.forEach((token, index) => {
    const group = token.key && token.key !== "is" ? token.key : `${token.key ?? "text"}-${index}`
    groups.set(group, [...(groups.get(group) ?? []), token])
  })
  // With a mode and is:open, the open slot has to be in that mode
  const modes = groups.get("mode")
  const onlyOpen = tokens.some(it => it.key === "is" && it.value.toLowerCase() === "open")

  return maps.filter(map =>
    Array.from(groups.values()).every(group => group.some(token => matches(map, token, user)))
    && (!modes || !onlyOpen || map.gamemodes.some(mode =>
      modes.some(it => [MODE_WORDS[mode.gamemode], mode.gamemode].includes(it.value.toLowerCase())) && !!openSpot(map, mode))))
}
