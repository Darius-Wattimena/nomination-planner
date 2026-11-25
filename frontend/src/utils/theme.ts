import {useState} from "react";

export type Theme = "dark" | "light"

function preferredTheme(): Theme {
  const stored = localStorage.getItem("theme")
  if (stored === "dark" || stored === "light") return stored
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark"
}

export function applyTheme(theme: Theme = preferredTheme()) {
  document.documentElement.dataset.theme = theme
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(preferredTheme)

  function toggle() {
    const next = theme === "dark" ? "light" : "dark"
    localStorage.setItem("theme", next)
    applyTheme(next)
    setTheme(next)
  }

  return {theme, toggle}
}
