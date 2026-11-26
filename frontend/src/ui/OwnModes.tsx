import {useRef, useState} from "react";
import type {Gamemode, User} from "../models/Types";
import {saveInterests} from "../resources/planner";
import {modesOf} from "../utils/users";
import Dialog from "./Dialog";
import {ModePicker} from "./ModeIcon";
import {useToast} from "./useToast";

/** For someone without a role in a mode, to choose the modes the website shows them first */
export default function OwnModesDialog({user, open, onClose}: {user: User, open: boolean, onClose: () => void}) {
  const toast = useToast()
  // Quick taps follow each other, so every one is shown at once and saved in order
  const [chosen, setChosen] = useState<Gamemode[]>()
  const saving = useRef<Promise<unknown>>(Promise.resolve())
  const shown = chosen ?? modesOf(user)

  function change(modes: Gamemode[]) {
    setChosen(modes)
    saving.current = saving.current.then(() => saveInterests(modes)).catch(() => {
      setChosen(undefined)
      toast("Your modes could not be saved, please try again", {kind: "error"})
    })
  }

  return (
    <Dialog open={open} onClose={onClose} title="Your modes">
      <div className="dialog-body own-modes">
        <p>Choose the modes you follow. The planner shows these first, for example in My modes on Beatmaps and on Nominators.</p>
        <ModePicker label="Modes you follow" selected={shown} onChange={change}/>
        <p className="muted">They don't change what you can do on the planner, that depends on your roles on osu!.</p>
      </div>
      <footer className="dialog-footer">
        <button className="button button-primary" onClick={onClose}>Done</button>
      </footer>
    </Dialog>
  )
}
