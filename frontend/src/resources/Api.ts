import type {AxiosRequestConfig} from "axios";
import type {BeatmapFilter, BeatmapStatus, Gamemode, PageLimit} from "../models/Types";

function toParams(values: object) {
  const params: Record<string, string> = {}
  Object.entries(values).forEach(([key, value]) => {
    const text = Array.isArray(value) ? value.join(",") : String(value ?? "")
    if (text !== "") params[key] = text
  })
  return params
}

const Api = {
  // Who is logged in, empty when nobody is
  session: (): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/session'
  }),
  logout: (): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/logout'
  }),
  addBeatmap: (osuId: string, gamemodes: Gamemode[]): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/beatmap/add',
    data: {osuId, gamemodes}
  }),
  deleteBeatmap: (id: string): AxiosRequestConfig => ({
    method: 'DELETE',
    url: `/v2/beatmap/${id}`
  }),
  updateBeatmapStatus: (id: string, status: BeatmapStatus): AxiosRequestConfig => ({
    method: 'PATCH',
    url: `/v2/beatmap/${id}/status`,
    params: {new: status}
  }),
  updateBeatmapNote: (id: string, note: string): AxiosRequestConfig => ({
    method: 'PATCH',
    url: `/v2/beatmap/${id}/note`,
    headers: {'Content-Type': 'text/plain'},
    data: note
  }),
  updateNominator: (id: string, gamemode: Gamemode, oldId: string, newId: string): AxiosRequestConfig => ({
    method: 'PATCH',
    url: `/v2/beatmap/${id}/${gamemode}/nominator`,
    params: {old: oldId, new: newId}
  }),
  fetchBeatmap: (id: string): AxiosRequestConfig => ({
    method: 'GET',
    url: `/v2/beatmap/${encodeURIComponent(id)}`
  }),
  // The newest of what happened to it
  fetchBeatmapEvents: (id: string): AxiosRequestConfig => ({
    method: 'GET',
    url: `/v2/beatmap/${encodeURIComponent(id)}/events`
  }),
  countBeatmaps: (filter: Partial<BeatmapFilter>): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/beatmap/count',
    params: toParams(filter)
  }),
  fetchBeatmaps: (filter: BeatmapFilter, pageNumber: number, pageLimit: PageLimit): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/beatmap/find/table',
    params: toParams({...filter, pageNumber, pageLimit})
  }),
  // Everything that is in progress, in a single call
  fetchRanked: (since: string | undefined): AxiosRequestConfig => ({
    method: "GET",
    url: "/v2/planner/ranked",
    params: {since}
  }),
  fetchPairings: (userId: string): AxiosRequestConfig => ({
    method: "GET",
    url: `/v2/profile/${userId}/pairing`
  }),
  saveInterests: (gamemodes: Gamemode[]): AxiosRequestConfig => ({
    method: 'PUT',
    url: '/v2/profile/me/interests',
    data: gamemodes
  }),
  fetchPlanner: (since: string | undefined): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/planner',
    params: {since}
  }),
  // Only answers once something changed, or after a while
  waitForPlanner: (since: string): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/planner/wait',
    params: {since}
  }),
  fetchSyncWaiting: (): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/admin/sync/waiting'
  }),
  syncUsers: (userIds: string[]): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/admin/sync/users/ids',
    params: {force: true},
    data: userIds
  }),
  // Syncs every pending beatmap when no status is given
  syncBeatmaps: (status: BeatmapStatus[]): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/admin/sync/beatmaps/pending',
    params: toParams({status})
  }),
  // Everyone in the BN, probationary BN and NAT on osu!, and who left them
  syncGroupMembers: (): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/admin/sync/users/groups'
  }),
  // The beatmaps nominated on osu! in the last days that are not on the planner
  importMissingBeatmaps: (days: number): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/admin/sync/beatmaps/missing',
    params: {days}
  }),
  // Every ranked beatmap with its history, users and role history, as a gzipped file
  exportRanked: (): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/developer/export',
    responseType: 'blob'
  }),
  // Tells what importing the file would do, unless dryRun is false, which starts it
  importRanked: (file: Blob, dryRun: boolean): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/developer/import',
    params: {dryRun},
    headers: {'Content-Type': 'application/octet-stream'},
    data: file
  }),
  fetchImportStatus: (): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/developer/import'
  }),
  fetchHistoryStatus: (): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/developer/history'
  }),
  // Starts, or continues, importing every ranked beatmap of osu!
  startHistory: (): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/developer/history/start'
  }),
  stopHistory: (): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/developer/history/stop'
  }),
  // Works out again who nominated which mode of the old ranked beatmaps
  guessHistoryModes: (): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/developer/history/modes'
  }),
  // Answers with the beatmap as osu! has it now
  syncBeatmap: (id: string): AxiosRequestConfig => ({
    method: 'POST',
    url: `/v2/beatmap/${id}/sync`
  })
}

export default Api
