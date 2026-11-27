import {useSyncExternalStore} from "react";
import {readStored, store} from "./storage";

/** Browser notifications while the planner is open, which someone has to turn on themselves */
const KEY = "notifyTurns"

const listeners = new Set<() => void>()
let chosen = readStored<boolean>(KEY) === true

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function notificationsSupported() {
  return typeof window !== "undefined" && "Notification" in window
}

/** Turned on, and the browser still allows it */
export function notifying() {
  return chosen && notificationsSupported() && Notification.permission === "granted"
}

export function useNotifying() {
  return useSyncExternalStore(subscribe, notifying)
}

function choose(value: boolean) {
  chosen = value
  store(KEY, value)
  listeners.forEach(listener => listener())
}

/** Asks the browser for permission only when turning it on. Returns why it could not be turned on. */
export async function toggleNotifying(): Promise<string | undefined> {
  if (notifying()) {
    choose(false)
    return undefined
  }
  if (!notificationsSupported()) return "This browser can't show notifications"
  const permission = Notification.permission === "granted" ? "granted" : await Notification.requestPermission()
  if (permission !== "granted") return "Notifications are blocked for the planner in this browser"
  choose(true)
  return undefined
}
