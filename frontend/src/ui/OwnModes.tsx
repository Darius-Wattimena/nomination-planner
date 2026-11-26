import {useState} from "react";
import type {Gamemode, User} from "../models/Types";
import {saveInterests} from "../resources/planner";
import {modesOf} from "../utils/users";
import Dialog from "./Dialog";
import {ModePicker} from "./ModeIcon";
import {useToast} from "./useToast";

/** For someone without a role in a mode, to choose the modes the website shows them first */
export default function OwnModesDialog({user, open, onClose}: {user: User, open: boolean, onClose: () => void}) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const chosen = modesOf(user)

  async function change(modes: Gamemode[]) {
    setBusy(true)
    try {
      await saveInterests(modes)
    } catch {
      toast("Your modes could not be saved, please try again", {kind: "error"})
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="Your modes">
      <div className="dialog-body own-modes" aria-busy={busy}>
        <p>Choose the modes you follow. The planner shows these first, for example in My modes on Beatmaps and on Nominators.</p>
        <ModePicker label="Modes you follow" selected={chosen} onChange={modes => void change(modes)}/>
        <p className="muted">They don't change what you can do on the planner, that depends on your roles on osu!.</p>
      </div>
      <footer className="dialog-footer">
        <button className="button button-primary" onClick={onClose}>Done</button>
      </footer>
    </Dialog>
  )
}
