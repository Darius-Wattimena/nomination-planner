export type UserRole = 'Mapper' | 'Nominator' | 'Probation' | 'NominationAssessment'
export type PageLimit = 'TEN' | 'TWENTY' | 'FIFTY'
export type BeatmapPage = 'PENDING' | 'RANKED' | 'GRAVEYARD'

export interface UserContext {
  // The backend leaves the user out when it could not be found
  user?: User
  accessToken: string
  refreshToken: string
  validUntilEpochMilli: number
  permission: RolePermission
}

export interface RolePermission {
  osuRole: UserRole
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

export interface UserGamemode {
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
  pairingGraved: number
}

export interface BeatmapGamemode {
  gamemode: Gamemode
  nominators: BeatmapNominator[]
  isReady: boolean
}

export interface BeatmapNominator {
  nominator: User
  hasNominated: boolean
}

export interface BeatmapFilter {
  search: string
  status: BeatmapStatus[]
  page: BeatmapPage
  nominators: string[]
  gamemodes: Gamemode[]
  missingNominator: Gamemode[]
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

export interface SyncInfo {
  duration: number
  totalSynced: number
}
