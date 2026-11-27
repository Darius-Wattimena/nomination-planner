import type {KeyboardEvent} from "react";
import {useEffect, useRef, useState} from "react";

/** A menu that opens from a button, and closes when clicking or tabbing outside of it, or with Escape */
export function usePopup<T extends HTMLElement>() {
  const [open, setOpen] = useState(false)
  const root = useRef<T>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const outside = (event: Event) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener("pointerdown", outside)
    document.addEventListener("focusin", outside)
    return () => {
      document.removeEventListener("pointerdown", outside)
      document.removeEventListener("focusin", outside)
    }
  }, [open])

  /** Closes and gives the focus back to the button */
  function close() {
    setOpen(false)
    button.current?.focus()
  }

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape" && open) close()
  }

  return {open, setOpen, root, button, close, onKeyDown}
}
