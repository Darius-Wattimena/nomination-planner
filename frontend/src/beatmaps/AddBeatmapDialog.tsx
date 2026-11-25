import {FormEvent, useState} from "react";
import {GAMEMODES} from "../Constants";
import {Beatmap, Gamemode} from "../models/Types";
import Api from "../resources/Api";
import {request} from "../resources/client";
import Cover from "../ui/Cover";
import Dialog from "../ui/Dialog";
import {ModePicker} from "../ui/ModeIcon";
import {useToast} from "../ui/Toast";
import {useSession} from "../utils/session";

const URL_REGEX = /^(?:https?:\/\/)?(?:osu|old)\.ppy\.sh\/(?:beatmapsets|s)\/(\d+)(?:.*#(osu|taiko|fruits|mania)\b)?/

function parse(text: string): {id?: string, mode?: Gamemode} {
  const value = text.trim()
  if (/^\d+$/.test(value)) return {id: value}
  const match = value.match(URL_REGEX)
  return match ? {id: match[1], mode: match[2] as Gamemode | undefined} : {}
}

interface AddBeatmapDialogProps {
  open: boolean
  onClose: () => void
  onAdded: (id: string) => void
}

export default function AddBeatmapDialog({open, onClose, onAdded}: AddBeatmapDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} title="Add beatmap">
      <AddBeatmap onClose={onClose} onAdded={onAdded}/>
    </Dialog>
  )
}

function AddBeatmap({onClose, onAdded}: {onClose: () => void, onAdded: (id: string) => void}) {
  const user = useSession()?.user
  const toast = useToast()
  const [text, setText] = useState("")
  const [picked, setPicked] = useState<Gamemode[]>()
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  const {id, mode} = parse(text)
  const ownModes = GAMEMODES.filter(it => user?.gamemodes.some(own => own.gamemode === it))
  // Use the mode of the link until something is picked by hand
  const modes = picked ?? (mode ? [mode] : ownModes)
  const invalidUrl = !id && (submitted || text.trim() !== "")

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (!id || modes.length === 0) return

    setBusy(true)
    setError(undefined)
    try {
      // An empty answer means that the beatmap could not be found on osu!
      const added = await request<Beatmap | "">(Api.addBeatmap(id, modes))
      if (added) {
        toast(`${added.title} is on the planner`)
        onAdded(id)
      } else {
        setError("This beatmap could not be found on osu!.")
      }
    } catch {
      setError("Something went wrong while adding the beatmap.")
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="dialog-body">
        <label className="field">
          <span>Beatmap link or ID</span>
          <input
            autoFocus
            value={text}
            placeholder="https://osu.ppy.sh/beatmapsets/…"
            aria-invalid={invalidUrl}
            aria-describedby="add-beatmap-help"
            onChange={event => {
              setText(event.target.value)
              setError(undefined)
            }}
          />
        </label>
        <p id="add-beatmap-help" className={invalidUrl ? "field-error" : "muted"}>
          {invalidUrl ? "This is not a valid osu! beatmap set link or ID." : "Paste a beatmap set link from osu.ppy.sh."}
        </p>
        {id && <div className="add-cover"><Cover key={id} id={id} size="cover"/></div>}
        <div className="field">
          <span>Game modes</span>
          <ModePicker selected={modes} onChange={setPicked} label="Game modes"/>
          {submitted && modes.length === 0 && <p className="field-error">Pick at least one game mode.</p>}
        </div>
        {error && <p className="alert alert-error" role="alert">{error}</p>}
      </div>
      <footer className="dialog-footer">
        <button type="button" className="button" disabled={busy} onClick={onClose}>Cancel</button>
        <button className="button button-primary" disabled={busy}>{busy ? "Adding…" : "Add beatmap"}</button>
      </footer>
    </form>
  )
}
