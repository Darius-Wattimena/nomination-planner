import {useState} from "react";
import {readText, store} from "./storage";

export type Theme = "dark" | "light"

function preferredTheme(): Theme {
  // The planner has always been dark, light is something to choose
  return readText("theme")?.replaceAll('"', "") === "light" ? "light" : "dark"
}

export function applyTheme(theme: Theme = preferredTheme()) {
  document.documentElement.dataset.theme = theme
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(preferredTheme)

  function toggle() {
    const next = theme === "dark" ? "light" : "dark"
    store("theme", next)
    applyTheme(next)
    setTheme(next)
  }

  return {theme, toggle}
}
