import {useSyncExternalStore} from "react";
import {readStored, storeFirst} from "../utils/storage";

export interface SavedView {
  name: string
  query: string
}

const KEY = "savedViews"

/** Stored by an older version or changed by hand, what isn't a view is left out */
function readViews(): SavedView[] {
  const stored = readStored<unknown>(KEY)
  if (!Array.isArray(stored)) return []
  return stored.filter((it: unknown): it is SavedView => {
    const view = it as Partial<SavedView> | null
    return typeof view?.name === "string" && view.name.trim() !== "" && typeof view.query === "string"
  })
}

/** Kept outside the page, so undoing a deletion still works after leaving it */
let views: SavedView[] | undefined
const listeners = new Set<() => void>()

export function getSavedViews(): SavedView[] {
  views ??= readViews()
  return views
}

export function setSavedViews(update: (views: SavedView[]) => SavedView[]) {
  views = update(getSavedViews())
  storeFirst(KEY, views)
  listeners.forEach(listener => listener())
}

/** Saves the view, replacing one with the same name */
export function saveView(view: SavedView) {
  setSavedViews(all => [...all.filter(it => it.name !== view.name), view])
}

/** Deletes the view and returns how to put it back where it was */
export function deleteView(view: SavedView) {
  const index = getSavedViews().findIndex(it => it.name === view.name)
  setSavedViews(all => all.filter(it => it.name !== view.name))
  return () => setSavedViews(all => all.some(it => it.name === view.name) ? all : [...all.slice(0, index), view, ...all.slice(index)])
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useSavedViews() {
  return useSyncExternalStore(subscribe, getSavedViews)
}
