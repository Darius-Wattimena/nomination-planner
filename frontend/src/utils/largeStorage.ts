/**
 * Room for what is too large for localStorage, like every ranked beatmap. IndexedDB can be missing or blocked, which must
 * never break the planner: reading then gives nothing and writing is skipped.
 */

const DATABASE = "planner"
const STORE = "caches"

let opening: Promise<IDBDatabase | undefined> | undefined

function open(): Promise<IDBDatabase | undefined> {
  opening ??= new Promise(resolve => {
    try {
      const request = indexedDB.open(DATABASE, 1)
      request.onupgradeneeded = () => request.result.createObjectStore(STORE)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => resolve(undefined)
      request.onblocked = () => resolve(undefined)
    } catch {
      resolve(undefined)
    }
  })
  return opening
}

function run<T>(mode: IDBTransactionMode, act: (store: IDBObjectStore) => IDBRequest): Promise<T | undefined> {
  return open().then(database => new Promise<T | undefined>(resolve => {
    if (!database) return resolve(undefined)
    try {
      const request = act(database.transaction(STORE, mode).objectStore(STORE))
      request.onsuccess = () => resolve(request.result as T)
      request.onerror = () => resolve(undefined)
    } catch {
      resolve(undefined)
    }
  }))
}

export function readLarge<T>(key: string): Promise<T | undefined> {
  return run<T>("readonly", store => store.get(key))
}

export function storeLarge(key: string, value: unknown): Promise<void> {
  return run("readwrite", store => store.put(value, key)).then(() => undefined)
}

export function forgetLarge(key: string): Promise<void> {
  return run("readwrite", store => store.delete(key)).then(() => undefined)
}
