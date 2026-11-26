import type {FormEvent, ReactNode} from "react";
import {useCallback, useEffect, useState} from "react";
import {useNavigate} from "react-router";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import {addBeatmap, NOBODY, setNominator, usePlanner} from "../resources/planner";
import Cover from "../ui/Cover";
import Dialog from "../ui/Dialog";
import {ModePicker} from "../ui/ModeIcon";
import {useToast} from "../ui/useToast";
import {parseOsuLink} from "../utils/osuLink";
import {canEdit, useSession} from "../utils/session";
import {canJoin} from "../utils/nominations";
import {nextStep} from "../utils/workflow";
import type {OpenAdd} from "./useAddBeatmap";
import {AddContext} from "./useAddBeatmap";

function isTyping(target: EventTarget | null) {
  return target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))
}

function parse(text: string) {
  const id = text.trim()
  return /^[0-9]{2,}$/.test(id) ? {id, mode: undefined} : parseOsuLink(id)
}

/** The window to add a beatmap. A copied link can be pasted anywhere, it opens the beatmap when it is on the planner already. */
export function AddBeatmapProvider({children}: {children: ReactNode}) {
  const session = useSession()
  const editor = canEdit(session)
  const loggedIn = !!session?.user
  const {maps = []} = usePlanner()
  const navigate = useNavigate()
  const toast = useToast()
  const [link, setLink] = useState<string>()
  const open = useCallback<OpenAdd>((initial = "") => setLink(initial), [])
  const close = () => setLink(undefined)

  useEffect(() => {
    if (!loggedIn) return
    const onPaste = (event: ClipboardEvent) => {
      const pasted = event.clipboardData?.getData("text").trim() ?? ""
      const found = parseOsuLink(pasted)
      if (isTyping(event.target) || !found) return
      if (maps.some(it => it.osuId === found.id)) {
        setLink(undefined)
        navigate(`/beatmaps?q=&map=${found.id}`)
      } else if (editor) {
        setLink(pasted)
      } else {
        toast("This beatmap is not on the planner", {kind: "error"})
      }
    }
    window.addEventListener("paste", onPaste)
    return () => window.removeEventListener("paste", onPaste)
  }, [loggedIn, editor, maps, navigate, toast])

  return (
    <AddContext.Provider value={open}>
      {children}
      <Dialog open={editor && link !== undefined} onClose={close} title="Add beatmap">
        <AddForm key={link} initial={link ?? ""} maps={maps} onClose={close} onOpen={id => {
          close()
          navigate(`/beatmaps?q=&map=${id}`)
        }}/>
      </Dialog>
    </AddContext.Provider>
  )
}

interface AddFormProps {
  initial: string
  maps: Beatmap[]
  onClose: () => void
  onOpen: (id: string) => void
}

function AddForm({initial, maps, onClose, onOpen}: AddFormProps) {
  const user = useSession()?.user
  const toast = useToast()
  const own = GAMEMODES.filter(it => user?.gamemodes.some(mine => mine.gamemode === it))
  const [text, setText] = useState(initial)
  const [picked, setPicked] = useState<Gamemode[]>()
  const [join, setJoin] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  const found = parse(text)
  const known = found && maps.find(it => it.osuId === found.id)
  // The mode of the link, until something is picked by hand
  const modes = picked ?? (found?.mode ? [found.mode] : own)
  const joinable = modes.filter(it => own.includes(it))
  const invalid = text.trim() !== "" && !found

  const canPaste = !!navigator.clipboard?.readText
  async function paste() {
    try {
      setText((await navigator.clipboard.readText()).trim())
    } catch {
      setError("Your browser didn't allow it. Paste the link in the field instead.")
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!found || busy) return
    if (known) return onOpen(known.osuId)
    if (modes.length === 0) return
    setBusy(true)
    setError(undefined)
    let added: Beatmap | undefined
    try {
      added = await addBeatmap(found.id, modes)
    } catch {
      setError("Could not add the beatmap. Try again.")
      return setBusy(false)
    }
    if (!added) {
      setError("osu! doesn't know this beatmap.")
      return setBusy(false)
    }
    let joined = true
    if (join && user) {
      for (const mode of joinable) {
        // It can be an older beatmap that was archived, so only take what is open
        const own = added.gamemodes.find(it => it.gamemode === mode)
        if (!own || !canJoin(added, own, user)) continue
        try {
          await setNominator(added.osuId, mode, NOBODY.osuId, user.osuId)
        } catch {
          joined = false
        }
      }
    }
    if (joined) toast(`${added.title} is on the planner`)
    else toast(`${added.title} is on the planner, but you could not be added as nominator`, {kind: "error"})
    onOpen(added.osuId)
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="dialog-body add-body">
        <div className="field">
          <label htmlFor="add-link">Link or ID on osu!</label>
          <span className="add-link"><input
            autoFocus
            id="add-link"
            autoComplete="off"
            inputMode="url"
            placeholder="https://osu.ppy.sh/beatmapsets/…"
            value={text}
            disabled={busy}
            aria-invalid={invalid}
            aria-describedby="add-help"
            onChange={event => {
              setText(event.target.value)
              setError(undefined)
            }}
          />
          {canPaste && text === "" && <button type="button" className="button button-tint" onClick={() => void paste()}>Paste</button>}</span>
          <span id="add-help" className={invalid ? "field-error" : "muted"}>
            {invalid ? "That is not a link to a beatmap set." : !found ? "Copy the link of the beatmap on osu!." : `Beatmap set ${found.id}`}
          </span>
        </div>
        {found && <div className="add-cover"><Cover key={found.id} id={found.id} size="cover"/></div>}
        {known
          ? <p className="alert" role="status"><span><strong>{known.title}</strong> is already on the planner. {nextStep(known, user?.osuId).text}.</span></p>
          : <>
            <div className="field">
              <span>Modes to nominate</span>
              <ModePicker selected={modes} onChange={setPicked} label="Game modes"/>
              {modes.length === 0 && <span className="field-error">Pick at least one.</span>}
            </div>
            {own.length > 0 &&
              <label className="check">
                <input type="checkbox" checked={join && joinable.length > 0} disabled={busy || joinable.length === 0} onChange={event => setJoin(event.target.checked)}/>
                <span>I will nominate it{joinable.length > 0 && <span className="muted"> for {joinable.map(it => GAMEMODE_NAMES[it]).join(" and ")}</span>}</span>
              </label>
            }
          </>
        }
        {error && <p className="alert alert-error" role="alert">{error}</p>}
      </div>
      <footer className="dialog-footer">
        <button type="button" className="button button-ghost" disabled={busy} onClick={onClose}>Cancel</button>
        <button className="button button-primary" disabled={busy || !found || (!known && modes.length === 0)}>{known ? "Open beatmap" : busy ? "Adding…" : "Add beatmap"}</button>
      </footer>
    </form>
  )
}
