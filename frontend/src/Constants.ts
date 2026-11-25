import {BeatmapStatus, Gamemode, UserRole} from "./models/Types";

export const MISSING_USER_ID = "0"

export const GAMEMODES = [Gamemode.Osu, Gamemode.Taiko, Gamemode.Catch, Gamemode.Mania]

export const GAMEMODE_NAMES: Record<Gamemode, string> = {
  [Gamemode.Osu]: "osu!",
  [Gamemode.Taiko]: "osu!taiko",
  [Gamemode.Catch]: "osu!catch",
  [Gamemode.Mania]: "osu!mania"
}

// Statuses that can be filtered on the pending page
export const PENDING_STATUSES = [
  BeatmapStatus.Qualified,
  BeatmapStatus.Nominated,
  BeatmapStatus.Disqualified,
  BeatmapStatus.Reset,
  BeatmapStatus.Pending,
  BeatmapStatus.Unfinished
]

export const ROLES: Record<UserRole, {name: string, short: string, className: string}> = {
  Nominator: {name: "Beatmap Nominator", short: "BN", className: "role-bn"},
  Probation: {name: "Probation Beatmap Nominator", short: "Probation", className: "role-pbn"},
  NominationAssessment: {name: "Nomination Assessment", short: "NAT", className: "role-nat"},
  Mapper: {name: "Mapper", short: "Mapper", className: "role-user"}
}
