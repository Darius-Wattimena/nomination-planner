import type {FormEvent, ReactNode} from "react";
import {createContext, useContext, useState} from "react";
import Modal from "react-modal";
import {useNavigate} from "react-router";
import {FaMagnifyingGlass, FaXmark} from "react-icons/fa6";
import type {User} from "../models/Types";
import {usePlanner} from "../resources/planner";
import {useSession} from "../utils/session";
import {nextStep} from "../utils/workflow";
import Cover from "./Cover";
import {Avatar} from "./Person";

const FindContext = createContext<() => void>(() => {})

export const useFind = () => useContext(FindContext)

/** Find a beatmap or nominator, whatever page you are on */
export function FindProvider({children}: {children: ReactNode}) {
  const loggedIn = !!useSession()?.user
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)

  return (
    <FindContext.Provider value={() => setOpen(true)}>
      {children}
      <Modal isOpen={loggedIn && open} onRequestClose={close} contentLabel="Find" className="find" overlayClassName="overlay overlay-top">
        <Find onClose={close}/>
      </Modal>
    </FindContext.Provider>
  )
}

function Find({onClose}: {onClose: () => void}) {
  const self = useSession()?.user
  const navigate = useNavigate()
  const {maps = []} = usePlanner()
  const [text, setText] = useState("")

  const wanted = text.trim().toLowerCase()
  const found = wanted === "" ? [] : maps.filter(it => `${it.artist} ${it.title} ${it.mapper.username} ${it.osuId}`.toLowerCase().includes(wanted)).slice(0, 8)
  const everybody = new Map<string, User>(maps.flatMap(it => it.gamemodes).flatMap(it => it.nominators).map(it => [it.nominator.osuId, it.nominator]))
  everybody.delete("0")
  const people = wanted === "" ? [] : Array.from(everybody.values()).filter(it => it.username.toLowerCase().includes(wanted)).slice(0, 4)

  function go(path: string) {
    navigate(path)
    onClose()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (found[0]) go(`/beatmaps?q=&map=${found[0].osuId}`)
    else if (people[0]) go(`/team?user=${people[0].osuId}`)
  }

  return (
    <>
      <form className="find-box" onSubmit={submit}>
        <FaMagnifyingGlass aria-hidden/>
        <input autoFocus id="find" type="search" enterKeyHint="search" aria-label="Find a beatmap or nominator" placeholder="Beatmap, mapper or nominator" autoComplete="off"
               value={text} onChange={event => setText(event.target.value)}/>
        <button type="button" className="icon-button" aria-label="Close" onClick={onClose}><FaXmark/></button>
      </form>
      {wanted !== "" &&
        <ul className="find-results" aria-label="Results">
          {found.map(it => (
            <li key={it.osuId}>
              <button onClick={() => go(`/beatmaps?q=&map=${it.osuId}`)}>
                <span className="map-thumb"><Cover id={it.osuId} size="card"/></span>
                <span className="find-text"><strong>{it.title}</strong><span className="muted">{it.artist} · {it.mapper.username}</span></span>
                <span className="muted find-step">{nextStep(it, self?.osuId).text}</span>
              </button>
            </li>
          ))}
          {people.map(it => (
            <li key={it.osuId}>
              <button onClick={() => go(`/team?user=${it.osuId}`)}>
                <span className="find-person"><Avatar user={it} size="large"/></span>
                <span className="find-text"><strong>{it.username}</strong><span className="muted">Nominator</span></span>
              </button>
            </li>
          ))}
          {found.length + people.length === 0 && <li className="find-none">Nothing in progress fits. Ranked beatmaps are in the archive.</li>}
        </ul>
      }
    </>
  )
}
