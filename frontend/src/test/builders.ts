import {MISSING_USER_ID} from "../Constants";
import type {Beatmap, BeatmapGamemode, User, UserRole} from "../models/Types";
import {BeatmapStatus, Gamemode} from "../models/Types";

export const NOBODY: User = {osuId: MISSING_USER_ID, username: "None", gamemodes: []}

/** A user with a role per mode, like {osu: "Nominator"} */
export function user(osuId: string, roles: Partial<Record<Gamemode, UserRole>> = {}, username = `User ${osuId}`): User {
  return {osuId, username, gamemodes: Object.entries(roles).map(([gamemode, role]) => ({gamemode: gamemode as Gamemode, role}))}
}

export const bn = (osuId: string, gamemode = Gamemode.Osu) => user(osuId, {[gamemode]: "Nominator"})
export const probation = (osuId: string, gamemode = Gamemode.Osu) => user(osuId, {[gamemode]: "Probation"})
export const mapper = user("100", {}, "Mapper")

/** A mode with its nominators, a nominator followed by true has nominated. Always holds two, like the backend sends. */
export function mode(gamemode: Gamemode, ...nominators: (User | [User, boolean])[]): BeatmapGamemode {
  const known = nominators.map(it => Array.isArray(it) ? {nominator: it[0], hasNominated: it[1]} : {nominator: it, hasNominated: false})
  while (known.length < 2) known.push({nominator: NOBODY, hasNominated: false})
  return {gamemode, nominators: known}
}

export function beatmap(changes: Partial<Beatmap> = {}): Beatmap {
  return {
    osuId: "1",
    artist: "Artist",
    title: "Title",
    note: "",
    mapper,
    status: BeatmapStatus.Pending,
    gamemodes: [mode(Gamemode.Osu)],
    dateAdded: "2026-01-01T00:00:00Z",
    dateUpdated: "2026-01-01T00:00:00Z",
    ...changes
  }
}
