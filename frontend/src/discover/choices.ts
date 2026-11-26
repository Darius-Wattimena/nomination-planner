import {useState} from "react";
import {readStored, store} from "../utils/storage";

export type Choice = "pass" | "like"

interface Entry {
  choice: Choice
  at: number
}

type Choices = Record<string, Entry>

/** Taste changes and so do beatmaps, a pass is not forever */
const PASS_DURATION = 30 * 86_400_000

function read(key: string): Choices {
  const stored = readStored<Choices>(key) ?? {}
  return Object.fromEntries(Object.entries(stored).filter(([, it]) => it.choice === "like" || Date.now() - it.at < PASS_DURATION))
}

/** What you thought of beatmaps, only kept in this browser */
export function useChoices(userId: string) {
  const key = `discover-${userId}`
  const [choices, setChoices] = useState<Choices>(() => read(key))

  function save(next: Choices) {
    setChoices(next)
    // Not being able to remember it must not get in the way
    store(key, next)
  }

  return {
    choices,
    choose: (id: string, choice: Choice) => save({...choices, [id]: {choice, at: Date.now()}}),
    forget: (id: string) => {
      const next = {...choices}
      delete next[id]
      save(next)
    },
    forgetPasses: () => save(Object.fromEntries(Object.entries(choices).filter(([, it]) => it.choice !== "pass")))
  }
}
