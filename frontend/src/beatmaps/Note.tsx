import {useEffect, useRef, useState} from "react";
import type {Beatmap} from "../models/Types";
import {saveNote} from "../resources/planner";

/** Saves by itself, there is nothing to remember */
export default function Note({beatmap, editable}: {beatmap: Beatmap, editable: boolean}) {
  const [note, setNote] = useState(beatmap.note ?? "")
  const [state, setState] = useState<"idle" | "dirty" | "saving" | "saved" | "failed">("idle")
  const latest = useRef({note, beatmap, dirty: false})
  useEffect(() => {
    latest.current = {...latest.current, note, beatmap}
  })

  async function save() {
    if (!latest.current.dirty) return
    latest.current.dirty = false
    const text = latest.current.note
    setState("saving")
    try {
      await saveNote(latest.current.beatmap, text)
      setState(latest.current.note === text ? "saved" : "dirty")
    } catch {
      latest.current.dirty = true
      setState("failed")
    }
  }

  useEffect(() => {
    if (state !== "dirty") return
    const timer = window.setTimeout(() => void save(), 900)
    return () => window.clearTimeout(timer)
  }, [note, state])

  // Closing the beatmap must not lose what was typed
  useEffect(() => () => void save(), [])

  if (!editable) {
    return beatmap.note ? <section className="map-note"><h3>Note</h3><p>{beatmap.note}</p></section> : null
  }
  return (
    <section className="map-note">
      <div className="note-heading">
        <h3><label htmlFor="beatmap-note">Note</label></h3>
        <span className={state === "failed" ? "field-error" : "muted"} role="status">
          {{idle: "", dirty: "", saving: "Saving…", saved: "Saved", failed: "Not saved. Keep typing to try again."}[state]}
        </span>
      </div>
      <textarea id="beatmap-note" rows={4} placeholder="What should the other nominators know?" value={note} onChange={event => {
        setNote(event.target.value)
        latest.current.dirty = true
        setState("dirty")
      }}/>
    </section>
  )
}
