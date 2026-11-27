import {useCallback, useEffect, useRef} from "react";
import {useLocation, useNavigate, useSearchParams} from "react-router";

/**
 * The opened beatmap is part of the URL. Opening it is a single step in the history, whatever number of beatmaps
 * you go through, and closing it takes that step back. So the back button does what you expect.
 */
export function useOpenMap() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  // Closing takes a moment, which must not go back twice
  const closing = useRef<string>(undefined)

  const selected = params.get("map") ?? undefined
  const opened = (location.state as {opened?: boolean} | null)?.opened

  function open(id: string) {
    const next = new URLSearchParams(params)
    next.set("map", id)
    setParams(next, selected ? {replace: true, state: location.state} : {state: {opened: true}})
  }

  function close() {
    if (!selected || closing.current === location.key) return
    closing.current = location.key
    if (opened) {
      navigate(-1)
    } else {
      const next = new URLSearchParams(params)
      next.delete("map")
      setParams(next, {replace: true})
    }
  }

  // The same function every time, so rows that get it don't have to render again
  const latest = useRef(open)
  useEffect(() => {
    latest.current = open
  })
  const stableOpen = useCallback((id: string) => latest.current(id), [])

  return {selected, open: stableOpen, close}
}
