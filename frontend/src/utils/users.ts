import {GAMEMODES, MISSING_USER_ID} from "../Constants";
import {Beatmap, BeatmapGamemode, Gamemode, User, UserRole} from "../models/Types";

const RANKING: UserRole[] = ["NominationAssessment", "Nominator", "Probation", "Mapper"]

export function highestRole(user: User, gamemode?: Gamemode): UserRole {
  const roles = user.gamemodes
    .filter(it => !gamemode || it.gamemode === gamemode)
    .map(it => it.role)
  return RANKING.find(role => roles.includes(role)) ?? "Mapper"
}

export function isMissing(user: User) {
  return user.osuId === MISSING_USER_ID
}

export function canNominate(user: User | undefined, gamemode: Gamemode) {
  return !!user && highestRole(user, gamemode) !== "Mapper"
}

export function isNominator(mode: BeatmapGamemode, userId: string) {
  return mode.nominators.some(it => it.nominator.osuId === userId)
}

export function onProbation(user: User, gamemode: Gamemode) {
  return highestRole(user, gamemode) === "Probation"
}

export function sortedModes(beatmap: Beatmap) {
  return [...beatmap.gamemodes].sort((a, b) => GAMEMODES.indexOf(a.gamemode) - GAMEMODES.indexOf(b.gamemode))
}

export function avatarUrl(userId: string) {
  return userId === MISSING_USER_ID
    ? "https://osu.ppy.sh/images/layout/avatar-guest.png"
    : `https://a.ppy.sh/${userId}`
}
