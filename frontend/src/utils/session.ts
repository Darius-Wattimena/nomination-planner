import {useSyncExternalStore} from "react";
import type {UserContext} from "../models/Types";
import {forget, readStored, storeFirst} from "./storage";

const KEY = "userContext"
const listeners = new Set<() => void>()
let current = read()

function read(): UserContext | undefined {
  const stored = readStored<UserContext>(KEY)
  return stored?.accessToken ? stored : undefined
}

function notify() {
  listeners.forEach(listener => listener())
}

export function getSession() {
  return current
}

export function setSession(session: UserContext | undefined) {
  current = session
  // osu! replaces the refresh token, so losing it means logging in again
  if (session) storeFirst(KEY, session)
  else forget(KEY)
  notify()
}

// Keep several open tabs on the same token, as osu! replaces the refresh token on every refresh
window.addEventListener("storage", event => {
  if (event.key === KEY || event.key === null) {
    current = read()
    notify()
  }
})

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useSession() {
  return useSyncExternalStore(subscribe, getSession)
}

export function canEdit(session: UserContext | undefined) {
  return !!session && session.permission.osuRole !== "Mapper"
}

/** The NAT and developers, the same permission the backend checks */
export function isAdmin(session: UserContext | undefined) {
  return !!session?.permission.roles.includes("ADMIN")
}
