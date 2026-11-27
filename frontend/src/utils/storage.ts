/**
 * What the planner keeps in this browser. Storage can be full or blocked, which must never break the planner:
 * reading then gives nothing and writing is skipped.
 */

/**
 * Everything stored as JSON carries this version. What another version stored reads as nothing, so a change to
 * what is stored only has to raise it. What was stored before versions existed is still read, except the caches. Plain text preferences like the theme and the volume have no version, there
 * is nothing in them to change and losing them would only annoy.
 */
export const STORAGE_VERSION = 2

interface Versioned {
  version: number
  value: unknown
}

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
    const stored: unknown = JSON.parse(localStorage.getItem(key) ?? "null")
    if (isVersioned(stored)) return stored.version === STORAGE_VERSION ? stored.value as T ?? undefined : undefined
    // Stored before there were versions. Caches load again, but what people chose themselves is kept.
    return CACHES.includes(key) ? undefined : stored as T ?? undefined
  } catch {
    return undefined
  }
}

function isVersioned(stored: unknown): stored is Versioned {
  return !!stored && typeof stored === "object" && !Array.isArray(stored) && typeof (stored as Partial<Versioned>).version === "number" && "value" in stored
}

function write(key: string, text: string): boolean {
  try {
    localStorage.setItem(key, text)
    return true
  } catch {
    return false
  }
}

/** Whether it was stored */
export function store(key: string, value: unknown): boolean {
  return write(key, JSON.stringify({version: STORAGE_VERSION, value} satisfies Versioned))
}

/** Store, dropping the caches when there is no room */
export function storeFirst(key: string, value: unknown): boolean {
  if (store(key, value)) return true
  CACHES.forEach(forget)
  return store(key, value)
}

/** A plain text preference, which makes room the same way */
export function storeText(key: string, text: string): boolean {
  if (write(key, text)) return true
  CACHES.forEach(forget)
  return write(key, text)
}

export function forget(key: string) {
  try {
    localStorage.removeItem(key)
  } catch {
    // Nothing was stored then
  }
}
