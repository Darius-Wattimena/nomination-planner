import {GAMEMODE_NAMES} from "../Constants";
import type {BeatmapEvent, User} from "../models/Types";
import {displayName} from "../utils/users";
import {toDate} from "../utils/workflow";

/** The first release that kept the history, nothing before it is known */
export const HISTORY_START = "2026-11-27"

function modes(event: BeatmapEvent) {
  const names = event.gamemodes.map(it => GAMEMODE_NAMES[it])
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : names[0] ?? ""
}

function name(user: User | undefined, fallback = "Someone") {
  return user ? displayName(user) : fallback
}

const OSU_EVENTS: Record<string, (who: string | undefined, where: string) => string> = {
  NOMINATE: (who, where) => `${who ?? "Someone"} nominated${where ? ` ${where}` : ""} on osu!`,
  QUALIFY: () => "Qualified on osu!",
  RANK: () => "Ranked on osu!",
  DISQUALIFY: who => who ? `${who} disqualified it on osu!` : "Disqualified on osu!",
  NOMINATION_RESET: who => who ? `${who} reset the nominations on osu!` : "The nominations were reset on osu!"
}

/** What happened in a single line, like "Rory joined osu!taiko" */
export function describeEvent(event: BeatmapEvent): string {
  const who = name(event.user)
  const where = modes(event)
  const nominator = name(event.nominator, "a nominator")
  const self = !!event.user && event.user.osuId === event.nominator?.osuId

  switch (event.type) {
    case "Added":
      return `${who} added it${where ? ` for ${where}` : ""}`
    case "NominatorJoined":
      return self ? `${who} joined ${where}` : `${who} added ${nominator} to ${where}`
    case "NominatorLeft":
      return self ? `${who} left ${where}` : `${who} removed ${nominator} from ${where}`
    case "NominatorReplaced":
      return `${who} replaced ${name(event.oldNominator, "a nominator")} with ${nominator} in ${where}`
    case "NoteChanged":
      return `${who} changed the note`
    case "ModeAdded":
      return `${who} added ${where}`
    case "ModeRemoved":
      return `${who} removed ${where}`
    case "Synced": {
      const synced = event.user ? `${who} synced it with osu!` : "Synced with osu!"
      return event.status ? `${synced}, now ${event.status}` : synced
    }
    case "StatusChanged": {
      const osu = event.osuEvent && OSU_EVENTS[event.osuEvent]
      if (osu) return osu(event.user && who, where)
      return `${who} changed the status to ${event.status ?? "something else"}`
    }
  }
}

/** How long ago, in words: "just now", "5 minutes ago", "3 days ago" */
export function timeAgo(value: string | number, now = Date.now()) {
  const date = toDate(value)
  if (!date) return ""
  const minutes = Math.floor((now - date.getTime()) / 60_000)
  const ago = (count: number, unit: string) => `${count} ${unit}${count === 1 ? "" : "s"} ago`
  if (minutes < 1) return "just now"
  if (minutes < 60) return ago(minutes, "minute")
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return ago(hours, "hour")
  const days = Math.floor(hours / 24)
  if (days < 14) return ago(days, "day")
  if (days < 60) return ago(Math.floor(days / 7), "week")
  if (days < 365) return ago(Math.floor(days / 30), "month")
  return ago(Math.floor(days / 365), "year")
}
