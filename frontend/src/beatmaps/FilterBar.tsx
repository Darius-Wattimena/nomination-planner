import {useEffect, useRef, useState} from "react";
import {FaMagnifyingGlass, FaUser, FaUserPlus, FaUsers, FaXmark} from "react-icons/fa6";
import {PENDING_STATUSES} from "../Constants";
import {Beatmap, BeatmapPage, User} from "../models/Types";
import {ModePicker} from "../ui/ModeIcon";
import {canEdit, useSession} from "../utils/session";
import {ListState} from "./filters";
import UserPicker from "./UserPicker";

interface FilterBarProps {
  page: BeatmapPage
  state: ListState
  update: (changes: Partial<ListState>) => void
  clear: () => void
  isFiltered: boolean
  beatmaps: Beatmap[]
}

export default function FilterBar({page, state, update, clear, isFiltered, beatmaps}: FilterBarProps) {
  const session = useSession()
  const userId = session?.user?.osuId
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<User[]>([])

  const others = state.nominators.filter(it => it !== userId)

  function nameOf(id: string) {
    const known = picked.find(it => it.osuId === id)
      ?? beatmaps.flatMap(it => it.gamemodes).flatMap(it => it.nominators).map(it => it.nominator).find(it => it.osuId === id)
    return known?.username ?? `User ${id}`
  }

  function toggleNominator(id: string) {
    update({
      nominators: state.nominators.includes(id)
        ? state.nominators.filter(it => it !== id)
        : [...state.nominators, id]
    })
  }

  return (
    <section className="filters" aria-label="Filters">
      <div className="filter-row">
        <SearchBox value={state.search} onChange={search => update({search})}/>
        <button className="button" onClick={() => setPicking(true)}><FaUsers/>Nominators</button>
        {isFiltered && <button className="button button-ghost" onClick={clear}><FaXmark/>Clear filters</button>}
      </div>
      <div className="filter-row">
        <ModePicker selected={state.modes} onChange={modes => update({modes})} label="Game modes"/>
        <div className="chips">
          {canEdit(session) && userId &&
            <button className="chip" aria-pressed={state.nominators.includes(userId)} onClick={() => toggleNominator(userId)}>
              <FaUser/>My icons
            </button>
          }
          {page !== "RANKED" &&
            <button className="chip" aria-pressed={state.missing} onClick={() => update({missing: !state.missing})}>
              <FaUserPlus/>Needs nominator
            </button>
          }
          {others.map(id => (
            <button key={id} className="chip" aria-pressed aria-label={`Remove ${nameOf(id)} from the filter`} onClick={() => toggleNominator(id)}>
              {nameOf(id)}<FaXmark/>
            </button>
          ))}
        </div>
      </div>
      {page === "PENDING" &&
        <div className="filter-row">
          <div className="chips" role="group" aria-label="Status">
            {PENDING_STATUSES.map(status => {
              const active = state.status.includes(status)
              return (
                <button
                  key={status}
                  className={`chip chip-status status-${status.toLowerCase()}`}
                  aria-pressed={active}
                  onClick={() => update({status: active ? state.status.filter(it => it !== status) : [...state.status, status]})}
                >
                  {status}
                </button>
              )
            })}
          </div>
        </div>
      }
      <UserPicker
        open={picking}
        onClose={() => setPicking(false)}
        title="Filter on nominators"
        selected={state.nominators}
        onPick={user => {
          setPicked(all => [...all, user])
          toggleNominator(user.osuId)
        }}
        initialModes={state.modes}
        multiple
      />
    </section>
  )
}

function SearchBox({value, onChange}: {value: string, onChange: (value: string) => void}) {
  const [text, setText] = useState(value)
  const timer = useRef<number>(undefined)

  // Follow the URL, for example when clearing the filters
  useEffect(() => {
    window.clearTimeout(timer.current)
    setText(value)
  }, [value])

  useEffect(() => () => window.clearTimeout(timer.current), [])

  return (
    <label className="search">
      <FaMagnifyingGlass aria-hidden/>
      <input
        type="search"
        aria-label="Search beatmaps"
        placeholder="Search artist, title or mapper"
        value={text}
        onChange={event => {
          const next = event.target.value
          setText(next)
          window.clearTimeout(timer.current)
          timer.current = window.setTimeout(() => onChange(next), 350)
        }}
      />
    </label>
  )
}
