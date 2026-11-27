import {useState} from "react";
import {readText, storeText} from "./storage";

export type Theme = "dark" | "light"

function preferredTheme(): Theme {
  // The planner has always been dark, light is something to choose
  return readText("theme")?.replaceAll('"', "") === "light" ? "light" : "dark"
}

/** The --bar of each theme, which phones also use around the page */
const BAR_COLORS: Record<Theme, string> = {dark: "#161d28", light: "#ffffff"}

export function applyTheme(theme: Theme = preferredTheme()) {
  document.documentElement.dataset.theme = theme
  // The theme is chosen on the planner, not by the system, so every variant gets its color
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach(it => it.content = BAR_COLORS[theme])
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(preferredTheme)

  function toggle() {
    const next = theme === "dark" ? "light" : "dark"
    storeText("theme", next)
    applyTheme(next)
    setTheme(next)
  }

  return {theme, toggle}
}
