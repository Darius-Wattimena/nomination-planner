/**
 * What the planner keeps in this browser. Storage can be full or blocked, which must never break the planner:
 * reading then gives nothing and writing is skipped.
 */

/** Caches that can be loaded again, so they make room for what can't */
const CACHES = ["planner", "ranked"]

/** A plain text value, as some preferences were stored before */
export function readText(key: string): string | undefined {
  try {
    return localStorage.getItem(key) ?? undefined
  } catch {
    return undefined
  }
}

export function readStored<T>(key: string): T | undefined {
  try {
    return JSON.parse(localStorage.getItem(key) ?? "null") ?? undefined
  } catch {
    return undefined
  }
}

/** Whether it was stored */
export function store(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value))
    return true
  } catch {
    return false
  }
}

/** Store, dropping the caches when there is no room */
export function storeFirst(key: string, value: unknown): boolean {
  if (store(key, value)) return true
  CACHES.forEach(forget)
  return store(key, value)
}

export function forget(key: string) {
  try {
    localStorage.removeItem(key)
  } catch {
    // Nothing was stored then
  }
}
