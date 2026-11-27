import {useCallback, useEffect, useState} from "react";
import {readStored, storeFirst} from "../utils/storage";

export type Choice = "pass" | "like"

interface Entry {
  choice: Choice
  /** Missing on likes from before it was kept */
  at?: number
}

export type Choices = Record<string, Entry>

/** Taste changes and so do beatmaps, a pass is not forever */
export const PASS_DAYS = 30
const PASS_DURATION = PASS_DAYS * 86_400_000

/** Leaves out passes that expired, and anything that isn't a choice */
export function stillValid(stored: unknown, now = Date.now()): Choices {
  if (!stored || typeof stored !== "object") return {}
  return Object.fromEntries(Object.entries(stored as Record<string, Partial<Entry> | null>).filter((entry): entry is [string, Entry] => {
    const it = entry[1]
    return it?.choice === "like" || (it?.choice === "pass" && typeof it.at === "number" && now - it.at < PASS_DURATION)
  }))
}

/** What you thought of beatmaps, only kept in this browser */
export function useChoices(userId: string) {
  const key = `discover-${userId}`
  const [state, setState] = useState(() => ({key, choices: stillValid(readStored(key))}))
  if (state.key !== key) setState({key, choices: stillValid(readStored(key))})
  const {choices} = state

  useEffect(() => {
    // Not being able to remember it must not get in the way
    storeFirst(state.key, state.choices)
  }, [state])

  const change = useCallback((next: (choices: Choices) => Choices) => setState(now => ({...now, choices: next(now.choices)})), [])
  const choose = useCallback((id: string, choice: Choice) => change(now => ({...now, [id]: {choice, at: Date.now()}})), [change])
  const forget = useCallback((...ids: string[]) => change(now => Object.fromEntries(Object.entries(now).filter(([id]) => !ids.includes(id)))), [change])
  const forgetPasses = useCallback(() => change(now => Object.fromEntries(Object.entries(now).filter(([, it]) => it.choice !== "pass"))), [change])

  return {choices, choose, forget, forgetPasses}
}
