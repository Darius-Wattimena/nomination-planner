export type UserRole = 'Mapper' | 'Nominator' | 'Probation' | 'NominationAssessment'
export type PageLimit = 'TEN' | 'TWENTY' | 'FIFTY'
export type BeatmapPage = 'RANKED' | 'GRAVEYARD'

export interface UserContext {
  // The backend leaves the user out when it could not be found
  user?: User
  permission: RolePermission
  /** Sent along with every change */
  csrfToken: string
}

interface RolePermission {
  /** VIEWER, EDITOR and ADMIN, the same the backend checks */
  roles: string[]
}

export interface User {
  osuId: string
  username: string
  gamemodes: UserGamemode[]
  /** Restricted on osu!, left out when not */
  restricted?: boolean
  /** Modes chosen by someone without a role in a mode, left out when there are none */
  interests?: Gamemode[]
}

interface UserGamemode {
  gamemode: Gamemode
  role: UserRole
}

export interface Beatmap {
  osuId: string
  artist: string
  title: string
  note: string
  mapper: User
  status: BeatmapStatus
  gamemodes: BeatmapGamemode[]
  dateAdded?: string | number
  dateUpdated?: string | number
  dateRanked?: string | number
  /** The modes that can be the main one, unknown for what was not synced since */
  mainGamemodes?: Gamemode[]
}

export interface Pairing {
  osuId: string
  name: string
  pairingPending: number
  pairingRanked: number
}

export interface BeatmapGamemode {
  gamemode: Gamemode
  nominators: BeatmapNominator[]
}

export interface BeatmapNominator {
  nominator: User
  hasNominated: boolean
}

export interface BeatmapFilter {
  search: string
  page: BeatmapPage
  nominators: string[]
  gamemodes: Gamemode[]
}

export enum Gamemode {
  Osu = "osu",
  Taiko = "taiko",
  Catch = "fruits",
  Mania = "mania"
}

export enum BeatmapStatus {
  Qualified = "Qualified",
  Nominated = "Nominated",
  Disqualified = "Disqualified",
  Reset = "Reset",
  Pending = "Pending",
  Ranked = "Ranked",
  Graved = "Graved",
  Unfinished = "Unfinished"
}

/** A sync runs in the background, this is how many it will go through */
export interface SyncQueued {
  queued: number
}
