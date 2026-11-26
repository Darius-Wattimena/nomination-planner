import type {AxiosError, AxiosRequestConfig} from "axios";
import axios from "axios";
import {useCallback, useEffect, useState} from "react";
import {CONFIG} from "../Settings";
import type {UserContext} from "../models/Types";
import {getSession, setSession} from "../utils/session";
import Api from "./Api";

const client = axios.create({baseURL: CONFIG.api_url})

let refreshing: Promise<void> | undefined

// Only one refresh may run at a time, as osu! invalidates a refresh token once it is used
function refreshSession(): Promise<void> {
  const session = getSession()
  if (!session) return Promise.reject(new Error("Not logged in"))

  if (!refreshing) {
    refreshing = client.request<UserContext>(Api.refresh(session.refreshToken))
      .then(result => setSession(result.data))
      .catch(error => {
        // Only a refused refresh ends the session, not an unreachable backend
        if ((error as AxiosError).response) setSession(undefined)
        throw error
      })
      .finally(() => {
        refreshing = undefined
      })
  }
  return refreshing
}

/** Perform a call on behalf of the logged-in user */
export async function request<T>(config: AxiosRequestConfig, signal?: AbortSignal): Promise<T> {
  const session = getSession()
  if (session && Date.now() >= session.validUntilEpochMilli) {
    await refreshSession()
  }

  const token = () => getSession()?.accessToken
  const call = () => client.request<T>({
    ...config,
    signal,
    headers: {...config.headers, ...(token() && {Authorization: `Bearer ${token()}`})}
  })

  try {
    return (await call()).data
  } catch (error) {
    if ((error as AxiosError).response?.status !== 401) throw error
    await refreshSession()
    return (await call()).data
  }
}

export async function login(code: string) {
  const result = await client.request<UserContext>(Api.login(code))
  setSession(result.data)
}

interface QueryState<T> {
  data?: T
  loading: boolean
  error: boolean
}

/** Load data, and load it again whenever the config changes. Keeps the previous data in the meantime. */
export function useQuery<T>(config: AxiosRequestConfig | undefined) {
  const [state, setState] = useState<QueryState<T>>({loading: !!config, error: false})
  const [version, setVersion] = useState(0)
  const key = config && JSON.stringify(config)

  // Show loading as soon as something else is asked for
  const asked = `${key}#${version}`
  const [loadingFor, setLoadingFor] = useState(asked)
  if (key && loadingFor !== asked) {
    setLoadingFor(asked)
    setState(previous => ({...previous, loading: true, error: false}))
  }

  useEffect(() => {
    if (!key) return
    const controller = new AbortController()

    request<T>(JSON.parse(key), controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setState({data, loading: false, error: false})
      })
      .catch(() => {
        if (!controller.signal.aborted) setState(previous => ({...previous, loading: false, error: true}))
      })

    return () => controller.abort()
  }, [key, version])

  const reload = useCallback(() => setVersion(it => it + 1), [])
  return {...state, reload}
}
