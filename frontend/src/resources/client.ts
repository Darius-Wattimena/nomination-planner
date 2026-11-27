import type {AxiosError, AxiosRequestConfig} from "axios";
import axios from "axios";
import {useCallback, useEffect, useState} from "react";
import {CONFIG} from "../Settings";
import type {UserContext} from "../models/Types";
import {announceSessionChange, getSession, onSessionChangeElsewhere, setSession, setSessionUnreachable} from "../utils/session";
import Api from "./Api";

// The login is a cookie of the backend, which the browser only sends along when asked to
const client = axios.create({baseURL: CONFIG.api_url, withCredentials: true})

/** Ask the backend who is logged in on this browser */
export async function loadSession() {
  try {
    const result = await client.request<UserContext | "">(Api.session())
    setSession(result.status === 200 && result.data ? result.data : undefined)
  } catch (error) {
    // Without an answer it is unknown who is logged in, the pages tell that instead of asking to log in
    if (isUnreachable(error)) setSessionUnreachable()
    else setSession(undefined)
  }
}

/** No answer at all, or the backend itself failed */
export function isUnreachable(error: unknown) {
  const status = statusOf(error)
  return status === undefined || status >= 500
}

onSessionChangeElsewhere(() => void loadSession())

function isChange(config: AxiosRequestConfig) {
  return !["GET", "HEAD", "OPTIONS"].includes((config.method ?? "GET").toUpperCase())
}

/** Perform a call on behalf of the logged-in user */
export async function request<T>(config: AxiosRequestConfig, signal?: AbortSignal): Promise<T> {
  // Changes need the token of the session, which a page of another site can't know
  const call = () => {
    const token = isChange(config) && getSession()?.csrfToken
    return client.request<T>({...config, signal, headers: {...config.headers, ...(token && {"X-CSRF-TOKEN": token})}})
  }

  try {
    return (await call()).data
  } catch (error) {
    const status = statusOf(error)
    const before = getSession()
    // Logged out, in another tab or because osu! no longer accepts the login
    if (status === 401 && before) await loadSession()
    // Logged in again in another tab, which gave the session a new token
    if (status === 403 && before && isChange(config)) {
      await loadSession()
      if (getSession() && getSession()?.csrfToken !== before.csrfToken) return (await call()).data
    }
    throw error
  }
}

/** Log out on this browser */
export async function logOutSession() {
  try {
    await request(Api.logout())
  } finally {
    setSession(undefined)
    announceSessionChange()
  }
}

export function statusOf(error: unknown) {
  return (error as AxiosError).response?.status
}

/** What the backend said went wrong, its errors are a ProblemDetail */
export function problemOf(error: unknown): string | undefined {
  const detail = (error as AxiosError<{detail?: unknown}>).response?.data?.detail
  return typeof detail === "string" && detail.trim() ? detail : undefined
}

/** Like request, where something that doesn't exist is undefined instead of an error */
export async function find<T>(config: AxiosRequestConfig, signal?: AbortSignal): Promise<T | undefined> {
  try {
    return await request<T>(config, signal)
  } catch (error) {
    if (statusOf(error) === 404) return undefined
    throw error
  }
}

interface QueryState<T> {
  data?: T
  loading: boolean
  error: boolean
}

/**
 * Load data, and load it again whenever the config changes. Keeps the previous data in the meantime.
 * With load set to find, something that doesn't exist is no data instead of an error.
 */
export function useQuery<T>(config: AxiosRequestConfig | undefined, load: (config: AxiosRequestConfig, signal: AbortSignal) => Promise<T | undefined> = request) {
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

    load(JSON.parse(key), controller.signal)
      .then(data => {
        if (!controller.signal.aborted) setState({data, loading: false, error: false})
      })
      .catch(() => {
        if (!controller.signal.aborted) setState(previous => ({...previous, loading: false, error: true}))
      })

    return () => controller.abort()
  }, [key, version, load])

  const reload = useCallback(() => setVersion(it => it + 1), [])
  return {...state, reload}
}
