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

export interface UserSearchFilter {
  username: string
  gamemodes: Gamemode[]
  roles: UserRole[]
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
