/** Keys that type something must not do something else while typing */
export function isTyping(target: EventTarget | null) {
  const element = target as HTMLElement | null
  if (!element?.tagName) return false
  return element.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(element.tagName)
}

/** A key on its own, not typed in a field and not part of a shortcut of the browser */
export function isShortcut(event: KeyboardEvent) {
  return !event.ctrlKey && !event.metaKey && !event.altKey && !event.defaultPrevented && !isTyping(event.target)
}

/** Space and Enter already press a focused button or link */
export function pressesFocused(event: KeyboardEvent) {
  return (event.key === " " || event.key === "Enter") && !!(event.target as HTMLElement | null)?.closest?.("button, a, summary")
}

/** A dialog lies over the page, so the keys of the page wait */
export const dialogOpen = () => document.body.classList.contains("ReactModal__Body--open")
