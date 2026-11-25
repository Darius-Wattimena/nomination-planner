import {AxiosRequestConfig} from "axios";
import {BeatmapFilter, BeatmapStatus, Gamemode, PageLimit, UserSearchFilter} from "../models/Types";

function toParams(values: object) {
  const params: Record<string, string> = {}
  Object.entries(values).forEach(([key, value]) => {
    const text = Array.isArray(value) ? value.join(",") : String(value ?? "")
    if (text !== "") params[key] = text
  })
  return params
}

const Api = {
  // The backend drops the last character of these two bodies: the '=' axios adds when form encoding a string
  login: (code: string): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/auth',
    data: code
  }),
  refresh: (refreshToken: string): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/auth/refresh',
    data: refreshToken
  }),
  addBeatmap: (osuId: string, gamemodes: Gamemode[]): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/beatmap/add',
    data: {osuId, gamemodes}
  }),
  deleteBeatmap: (id: string): AxiosRequestConfig => ({
    method: 'DELETE',
    url: `/v2/beatmap/${id}/delete`
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
  searchUsers: (filter: UserSearchFilter): AxiosRequestConfig => ({
    method: 'GET',
    url: '/v2/user/search',
    params: toParams(filter)
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
  syncBeatmap: (id: string): AxiosRequestConfig => ({
    method: 'POST',
    url: '/v2/admin/sync/beatmaps/single',
    params: {id}
  })
}

export default Api
