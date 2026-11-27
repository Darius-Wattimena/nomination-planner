import type {FormEvent, ReactNode} from "react";
import {useEffect, useState} from "react";
import Modal from "react-modal";
import {Link, useNavigate} from "react-router";
import {FaMagnifyingGlass, FaPlus, FaXmark} from "react-icons/fa6";
import MapView from "../beatmaps/MapView";
import {useAddBeatmap} from "../beatmaps/useAddBeatmap";
import {usePlanner} from "../resources/planner";
import {canEdit, useSession} from "../utils/session";
import {nextStep} from "../utils/workflow";
import Cover from "./Cover";
import {Avatar} from "./Person";
import {FindContext} from "./useFind";
import {focusesByItself} from "../utils/phone";
import {displayName} from "../utils/users";
import {parseBeatmap} from "../utils/osuLink";
import {isShortcut} from "../utils/keys";

/** Find a beatmap or nominator, whatever page you are on. A beatmap opens over that page. */
export function FindProvider({children}: {children: ReactNode}) {
  const loggedIn = !!useSession()?.user
  const [open, setOpen] = useState(false)
  const [shown, setShown] = useState<string>()
  const close = () => setOpen(false)

  // Slash opens it from anywhere, like on many sites, unless you are typing
  useEffect(() => {
    if (!loggedIn) return
    function onKey(event: KeyboardEvent) {
      if (event.key !== "/" || !isShortcut(event)) return
      event.preventDefault()
      setOpen(true)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [loggedIn])

  return (
    <FindContext.Provider value={() => setOpen(true)}>
      {children}
      <Modal isOpen={loggedIn && open} onRequestClose={close} contentLabel="Find" className="find" overlayClassName="overlay overlay-top">
        <Find onClose={close} onOpen={id => {
          close()
          setShown(id)
        }}/>
      </Modal>
      <MapView id={shown} onOpen={setShown} onClose={() => setShown(undefined)}/>
    </FindContext.Provider>
  )
}

function Find({onClose, onOpen}: {onClose: () => void, onOpen: (id: string) => void}) {
  const session = useSession()
  const self = session?.user
  const navigate = useNavigate()
  const openAdd = useAddBeatmap()
  const {maps = [], nominators = []} = usePlanner()
  const [text, setText] = useState("")

  const wanted = text.trim().toLowerCase()
  // A pasted link or ID finds its beatmap, or offers to add it
  const link = parseBeatmap(text)
  const linked = link && maps.find(it => it.osuId === link.id)
  const matching = wanted === "" ? [] : maps.filter(it => it !== linked && `${it.artist} ${it.title} ${it.mapper.username} ${it.osuId}`.toLowerCase().includes(wanted))
  const found = [...(linked ? [linked] : []), ...matching].slice(0, 8)
  // Everybody who can nominate, also when they are not on anything right now
  const people = wanted === "" ? [] : nominators.filter(it => it.username.toLowerCase().includes(wanted)).slice(0, 4)
  const addable = !!link && !linked && canEdit(session)

  function go(path: string) {
    navigate(path)
    onClose()
  }

  function add() {
    onClose()
    openAdd(text.trim())
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (found[0]) onOpen(found[0].osuId)
    else if (addable) add()
    else if (people[0]) go(`/team?user=${people[0].osuId}`)
  }

  return (
    <>
      <form className="find-box" onSubmit={submit}>
        <FaMagnifyingGlass aria-hidden/>
        <input autoFocus={focusesByItself()} id="find" type="search" enterKeyHint="search" aria-label="Find a beatmap or nominator" placeholder="Beatmap, mapper, nominator or link" autoComplete="off"
               value={text} onChange={event => setText(event.target.value)}/>
        <button type="button" className="icon-button" aria-label="Close" onClick={onClose}><FaXmark/></button>
      </form>
      {wanted !== "" &&
        <ul className="find-results" aria-label="Results">
          {addable &&
            <li>
              <button onClick={add}>
                <span className="map-thumb"><Cover id={link.id} size="card"/></span>
                <span className="find-text"><strong>Add this beatmap</strong><span className="muted">Beatmap set {link.id} is not on the planner yet</span></span>
                <FaPlus aria-hidden className="find-step"/>
              </button>
            </li>
          }
          {found.map(it => (
            <li key={it.osuId}>
              <button onClick={() => onOpen(it.osuId)}>
                <span className="map-thumb"><Cover id={it.osuId} size="card"/></span>
                <span className="find-text"><strong>{it.title}</strong><span className="muted">{it.artist} · {displayName(it.mapper)}</span></span>
                <span className="muted find-step">{nextStep(it, self?.osuId).text}</span>
              </button>
            </li>
          ))}
          {people.map(it => (
            <li key={it.osuId}>
              <button onClick={() => go(`/team?user=${it.osuId}`)}>
                <span className="find-person"><Avatar user={it} size="large"/></span>
                <span className="find-text"><strong>{displayName(it)}</strong><span className="muted">Nominator</span></span>
              </button>
            </li>
          ))}
          {found.length + people.length === 0 && !addable &&
            <li className="find-none">
              Nothing in progress fits. <Link to={`/ranked?q=${encodeURIComponent(text.trim())}`} onClick={onClose}>Search the archive for “{text.trim()}”</Link>
            </li>
          }
        </ul>
      }
    </>
  )
}
