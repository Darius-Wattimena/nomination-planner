import {useSyncExternalStore} from "react";
import type {UserContext} from "../models/Types";
import {forget, store} from "./storage";

/** Tells other tabs that someone logged out, or in */
const CHANGED = "sessionChanged"
const listeners = new Set<() => void>()
let current: UserContext | undefined
let known = false
let unreachable = false

// Before the login moved to a cookie the osu! tokens were kept here
forget("userContext")

function notify() {
  listeners.forEach(listener => listener())
}

export function getSession() {
  return current
}

export function setSession(session: UserContext | undefined) {
  current = session
  known = true
  unreachable = false
  notify()
}

/** The backend didn't answer, so who is logged in is still unknown. Whoever was known stays. */
export function setSessionUnreachable() {
  known = true
  unreachable = true
  notify()
}

export function isSessionUnreachable() {
  return unreachable
}

/** Let the other open tabs find out who is logged in again */
export function announceSessionChange() {
  store(CHANGED, Date.now())
}

export function onSessionChangeElsewhere(listener: () => void) {
  window.addEventListener("storage", event => {
    if (event.key === CHANGED) listener()
  })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useSession() {
  return useSyncExternalStore(subscribe, getSession)
}

/** Whether the backend told who is logged in yet */
export function useSessionKnown() {
  return useSyncExternalStore(subscribe, () => known)
}

/** Whether the backend could not be reached the last time it was asked */
export function useSessionUnreachable() {
  return useSyncExternalStore(subscribe, isSessionUnreachable)
}

export function canEdit(session: UserContext | undefined) {
  return !!session?.permission.roles.includes("EDITOR")
}

/** The NAT and developers, the same permission the backend checks */
export function isAdmin(session: UserContext | undefined) {
  return !!session?.permission.roles.includes("ADMIN")
}

/** Only the developers, who may export and import ranked beatmaps */
export function isDeveloper(session: UserContext | undefined) {
  return !!session?.permission.roles.includes("DEVELOPER")
}
