import {useCallback, useRef} from "react";

export type Fade = "start" | "end" | "both"

/** Which edges have more to scroll to, a pixel or two less is rounding */
export function fadeOf(left: number, width: number, visible: number): Fade | undefined {
  const start = left > 2
  const end = left + visible < width - 2
  return start && end ? "both" : start ? "start" : end ? "end" : undefined
}

/** Marks a row that scrolls sideways with data-fade, so the edge with more fades out */
export function useScrollFade<T extends HTMLElement>() {
  const cleanup = useRef<() => void>(undefined)
  return useCallback((element: T | null) => {
    cleanup.current?.()
    cleanup.current = undefined
    if (!element) return
    const update = () => {
      const fade = fadeOf(element.scrollLeft, element.scrollWidth, element.clientWidth)
      if (fade) element.dataset.fade = fade
      else delete element.dataset.fade
    }
    update()
    // Its size changes with the window, what is in it when a view is saved or deleted
    const observer = new ResizeObserver(update)
    observer.observe(element)
    const changes = new MutationObserver(update)
    changes.observe(element, {childList: true, subtree: true})
    element.addEventListener("scroll", update, {passive: true})
    cleanup.current = () => {
      observer.disconnect()
      changes.disconnect()
      element.removeEventListener("scroll", update)
    }
  }, [])
}
