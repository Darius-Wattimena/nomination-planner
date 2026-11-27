import {useSyncExternalStore} from "react";

/**
 * A time that only moves on once the day changed. Ages are shown in days, so what shows them renders again only then,
 * also in rows that are memoised and in a tab that stays open for days.
 */
let today = Date.now()
const listeners = new Set<() => void>()
let timer: number | undefined

function isSameDay(a: number, b: number) {
  return new Date(a).toDateString() === new Date(b).toDateString()
}

export function getToday() {
  return today
}

/** Moves on when the day changed since the last time */
export function checkToday(now = Date.now()) {
  if (isSameDay(now, today)) return
  today = now
  listeners.forEach(listener => listener())
}

function onVisible() {
  if (document.visibilityState === "visible") checkToday()
}

/** Checks just after midnight. Hidden tabs can be late with timers, so becoming visible checks too. */
function schedule() {
  window.clearTimeout(timer)
  const midnight = new Date(today).setHours(24, 0, 1, 0)
  timer = window.setTimeout(() => {
    checkToday()
    schedule()
  }, Math.max(1000, midnight - Date.now()))
}

export function subscribeToday(listener: () => void) {
  if (listeners.size === 0) {
    schedule()
    document.addEventListener("visibilitychange", onVisible)
  }
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
    if (listeners.size > 0) return
    window.clearTimeout(timer)
    document.removeEventListener("visibilitychange", onVisible)
  }
}

export function useToday() {
  return useSyncExternalStore(subscribeToday, getToday)
}
