import {GAMEMODES, MISSING_USER_ID} from "../Constants";
import type {Beatmap, BeatmapGamemode, Gamemode, User, UserRole} from "../models/Types";

const RANKING: UserRole[] = ["NominationAssessment", "Nominator", "Probation", "Former", "Mapper"]

/** The roles of the groups that can nominate now, only these give more than viewing */
export function canNominateAs(role: UserRole) {
  return role === "Nominator" || role === "Probation" || role === "NominationAssessment"
}

export function highestRole(user: User, gamemode?: Gamemode): UserRole {
  const roles = user.gamemodes
    .filter(it => !gamemode || it.gamemode === gamemode)
    .map(it => it.role)
  return RANKING.find(role => roles.includes(role)) ?? "Mapper"
}

/** Modes in which the user has a role, these decide what they may do */
export function roleModes(user: User | undefined): Gamemode[] {
  return GAMEMODES.filter(mode => user?.gamemodes.some(it => it.gamemode === mode && canNominateAs(it.role)))
}

/** Whether the user picks their modes themselves, as they have no role in any */
export function choosesOwnModes(user: User | undefined) {
  return !!user && roleModes(user).length === 0
}

/** The modes the website shows first: those with a role, or else the ones they chose */
export function modesOf(user: User | undefined): Gamemode[] {
  if (!user) return []
  const roles = roleModes(user)
  return roles.length > 0 ? roles : GAMEMODES.filter(it => user.interests?.includes(it))
}

export function isMissing(user: User) {
  return user.osuId === MISSING_USER_ID
}

export function canNominate(user: User | undefined, gamemode: Gamemode) {
  return !!user && canNominateAs(highestRole(user, gamemode))
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

/** What the backend stores as the name of users that osu! can't find, which is what happens to restricted users */
export const RESTRICTED_NAME = "RESTRICTED"

/** The name to show, as restricted users have none */
export function displayName(user: User) {
  return user.username === RESTRICTED_NAME ? "Restricted user" : user.username
}
