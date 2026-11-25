import {useSyncExternalStore} from "react";
import {UserContext} from "../models/Types";

const KEY = "userContext"
const listeners = new Set<() => void>()
let current = read()

function read(): UserContext | undefined {
  try {
    const stored = JSON.parse(localStorage.getItem(KEY) ?? "null")
    return stored?.accessToken ? stored : undefined
  } catch {
    return undefined
  }
}

function notify() {
  listeners.forEach(listener => listener())
}

export function getSession() {
  return current
}

export function setSession(session: UserContext | undefined) {
  current = session
  if (session) {
    localStorage.setItem(KEY, JSON.stringify(session))
  } else {
    localStorage.removeItem(KEY)
  }
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

export function isNat(session: UserContext | undefined) {
  return session?.permission.osuRole === "NominationAssessment"
}
