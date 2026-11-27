import {useEffect, useRef, useState} from "react";
import type {Beatmap} from "../models/Types";
import {saveNote} from "../resources/planner";
import {useToast} from "../ui/useToast";

/** The backend refuses longer notes */
const MAX_LENGTH = 2000

/** Saves by itself, there is nothing to remember */
export default function Note({beatmap, editable}: {beatmap: Beatmap, editable: boolean}) {
  const remote = beatmap.note ?? ""
  const [note, setNote] = useState(remote)
  const [state, setState] = useState<"idle" | "dirty" | "saving" | "saved" | "failed">("idle")
  const toast = useToast()
  const latest = useRef({note, beatmap, toast, dirty: false, closed: false})
  useEffect(() => {
    Object.assign(latest.current, {note, beatmap, toast})
  })

  // Follow changes of others, unless that would throw away what is being typed
  const [seen, setSeen] = useState(remote)
  const [sent, setSent] = useState<string>()
  const [overtaken, setOvertaken] = useState(false)
  if (remote !== seen) {
    setSeen(remote)
    if (state === "idle" || state === "saved") setNote(remote)
    else if (remote !== note && remote !== sent) setOvertaken(true)
  }

  async function save() {
    if (!latest.current.dirty) return
    latest.current.dirty = false
    const text = latest.current.note
    setSent(text)
    setState("saving")
    try {
      await saveNote(latest.current.beatmap, text)
      setState(latest.current.note === text ? "saved" : "dirty")
      setOvertaken(false)
    } catch {
      latest.current.dirty = true
      setState("failed")
      // Once closed, the only place left to say so
      if (latest.current.closed) latest.current.toast("Note not saved", {kind: "error"})
    }
  }

  useEffect(() => {
    if (state !== "dirty") return
    const timer = window.setTimeout(() => void save(), 900)
    return () => window.clearTimeout(timer)
  }, [note, state])

  // Closing the beatmap must not lose what was typed
  useEffect(() => {
    const current = latest.current
    current.closed = false
    return () => {
      current.closed = true
      void save()
    }
  }, [])

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
      {overtaken && <p className="alert alert-warning" role="alert">Someone else changed this note. Saving yours replaces theirs.</p>}
      <textarea id="beatmap-note" rows={4} maxLength={MAX_LENGTH} placeholder="What should the other nominators know?" value={note} onChange={event => {
        setNote(event.target.value)
        latest.current.dirty = true
        setState("dirty")
      }}/>
      {note.length > MAX_LENGTH - 200 && <span className="muted note-count">{note.length} of {MAX_LENGTH} characters</span>}
    </section>
  )
}
