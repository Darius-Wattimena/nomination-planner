import type {KeyboardEvent, ReactNode} from "react";
import {useEffect, useMemo, useRef, useState} from "react";
import {usePopup} from "../ui/usePopup";
import {FaCheck, FaChevronDown, FaFilter, FaMagnifyingGlass, FaRegCircleQuestion, FaXmark} from "react-icons/fa6";
import {useHelp} from "../ui/useHelp";
import {GAMEMODE_NAMES, GAMEMODES, PENDING_STATUSES} from "../Constants";
import type {Beatmap, User} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {Avatar} from "../ui/Person";
import type {Token} from "../utils/query";
import {filterMaps, formatQuery, MODE_WORDS, parseQuery} from "../utils/query";
import {useSession} from "../utils/session";
import {LABELS, STAGES} from "../utils/workflow";
import {isMissing} from "../utils/users";
import {focusesByItself} from "../utils/phone";

interface Option {
  key: string
  value: string
  label: string
  icon?: ReactNode
  /** Other ways to write the same, for queries that are typed */
  also?: string
}

const MODES = GAMEMODES.map((it): Option => ({key: "mode", value: MODE_WORDS[it], label: GAMEMODE_NAMES[it], icon: <ModeIcon mode={it}/>, also: it}))
const STATUSES = PENDING_STATUSES.map((it): Option => ({key: "status", value: it.toLowerCase(), label: it}))
const STAGE_OPTIONS = STAGES.map((it): Option => ({key: "stage", value: it.id, label: it.label}))
const MORE: Option[] = [
  {key: "is", value: "open", label: LABELS.open},
  {key: "is", value: "mine", label: "I'm nominating"},
  {key: "is", value: "move", label: LABELS.move},
  {key: "is", value: "stale", label: LABELS.stale},
  {key: "is", value: "noted", label: "Has a note"}
]
const MORE_AND_STAGES = [...MORE, ...STAGE_OPTIONS]

// The keys each menu picks from
const KEYS = {
  mode: ["mode"],
  bn: ["bn"],
  by: ["by"],
  status: ["status"],
  is: ["is"],
  isAndStage: ["is", "stage"],
}

function isOption(token: Token, option: Option) {
  const value = token.value.toLowerCase()
  return token.key === option.key && (value === option.value.toLowerCase() || value === option.also?.toLowerCase())
}

function peopleOptions(key: string, users: User[], self: User | undefined): Option[] {
  const unique = new Map(users.filter(it => !isMissing(it)).map(it => [it.osuId, it]))
  return Array.from(unique.values())
    .sort((a, b) => Number(b.osuId === self?.osuId) - Number(a.osuId === self?.osuId) || a.username.localeCompare(b.username))
    .map(it => it.osuId === self?.osuId
      ? {key, value: "me", label: "Me", icon: <Avatar user={it}/>, also: it.osuId}
      : {key, value: it.osuId, label: it.username, icon: <Avatar user={it}/>, also: it.username})
}

interface FiltersProps {
  query: string
  onChange: (query: string) => void
  maps: Beatmap[]
  /** The columns show the stages already */
  stages: boolean
}

/** Search, and a menu for everything that can be filtered on. What is typed as bn:name ends up in the menus. */
export default function Filters({query, onChange, maps, stages}: FiltersProps) {
  const self = useSession()?.user
  const help = useHelp()
  const tokens = useMemo(() => parseQuery(query), [query])
  const filters = tokens.filter(it => it.key)
  const words = formatQuery(tokens.filter(it => !it.key))
  const [text, setText] = useState(words)
  const [shown, setShown] = useState(false)

  // Follow changes from outside, without touching what is being typed
  const [followed, setFollowed] = useState(words)
  if (followed !== words) {
    setFollowed(words)
    if (formatQuery(parseQuery(text).filter(it => !it.key)) !== words) setText(words)
  }

  const nominators = useMemo(() => peopleOptions("bn", maps.flatMap(it => it.gamemodes).flatMap(it => it.nominators).map(it => it.nominator), self), [maps, self])
  const mappers = useMemo(() => peopleOptions("by", maps.map(it => it.mapper), self), [maps, self])
  const showStages = stages || filters.some(it => it.key === "stage")

  function apply(next: string) {
    const typed = parseQuery(next)
    // A filter is finished once there is a space behind it
    const finished = typed.filter((it, index) => it.key && (next.endsWith(" ") || index < typed.length - 1))
    const rest = typed.filter(it => !finished.includes(it))
    setText(finished.length > 0 ? formatQuery(rest) : next)
    onChange(formatQuery([...filters, ...finished, ...rest.filter(it => !it.key)]))
  }

  const menu = {tokens, maps, self, onChange: (next: Token[]) => onChange(formatQuery(next))}

  return (
    <div className="filters" role="search" aria-label="Filter beatmaps">
      <div className="search">
        <FaMagnifyingGlass aria-hidden/>
        <input
          id="query"
          type="search"
          enterKeyHint="search"
          aria-label="Search beatmaps"
          placeholder="Search title, artist or note"
          autoComplete="off"
          value={text}
          onChange={event => apply(event.target.value)}
          onKeyDown={event => {
            if (event.key === "Enter") apply(`${text} `)
          }}
        />
        {text && <button className="icon-button" aria-label="Clear the search" onClick={() => {
          setText("")
          onChange(formatQuery(filters))
        }}><FaXmark/></button>}
        <button className="icon-button search-help" aria-label="How searching works" title="How searching works" onClick={help}><FaRegCircleQuestion/></button>
      </div>
      <button className="filter-button filters-toggle" aria-expanded={shown} data-active={filters.length > 0 || undefined} onClick={() => setShown(!shown)}>
        <FaFilter aria-hidden/><span>{filters.length > 0 ? `Filters: ${filters.length}` : "Filters"}</span>
      </button>
      <div className="filter-menus" data-shown={shown || undefined}>
      <FilterMenu {...menu} label="Mode" keys={KEYS.mode} options={MODES}/>
      <FilterMenu {...menu} label="Nominator" keys={KEYS.bn} options={nominators} searchable/>
      <FilterMenu {...menu} label="Mapper" keys={KEYS.by} options={mappers} searchable/>
      <FilterMenu {...menu} label="Status" keys={KEYS.status} options={STATUSES}/>
      <FilterMenu {...menu} label="More" keys={showStages ? KEYS.isAndStage : KEYS.is} options={showStages ? MORE_AND_STAGES : MORE}/>
      {filters.length > 0 && <button className="button button-ghost" onClick={() => onChange(words)}>Clear filters</button>}
      </div>
    </div>
  )
}

interface FilterMenuProps {
  label: string
  keys: string[]
  options: Option[]
  searchable?: boolean
  tokens: Token[]
  maps: Beatmap[]
  self?: User
  onChange: (tokens: Token[]) => void
}

function FilterMenu({label, keys, options, searchable, tokens, maps, self, onChange}: FilterMenuProps) {
  const {open, setOpen, root, button, close, onKeyDown} = usePopup<HTMLDivElement>()
  const [end, setEnd] = useState(false)

  const own = useMemo(() => tokens.filter(it => it.key && keys.includes(it.key)), [tokens, keys])
  // Something typed can be part of a name, so it isn't always one of the options
  const all = useMemo(() => [
    ...own.filter(token => !options.some(it => isOption(token, it))).map((token): Option => ({key: token.key!, value: token.value, label: token.value})),
    ...options
  ], [own, options])
  const picked = all.filter(option => own.some(it => isOption(it, option)))

  function toggle(option: Option) {
    onChange(tokens.some(it => isOption(it, option)) ? tokens.filter(it => !isOption(it, option)) : [...tokens, {key: option.key, value: option.value}])
  }

  return (
    <div className="filter" ref={root} data-open={open || undefined} onKeyDown={onKeyDown} onClick={event => event.target === root.current && setOpen(false)}>
      <button ref={button} className="filter-button" aria-expanded={open} aria-haspopup="dialog" data-active={picked.length > 0 || undefined} onClick={event => {
        setEnd(event.currentTarget.getBoundingClientRect().left + 300 > window.innerWidth)
        setOpen(!open)
      }}>
        <span>{picked.length === 0 ? label : <><span className="filter-name">{label}</span> {picked[0].label}{picked.length > 1 && ` +${picked.length - 1}`}</>}</span>
        <FaChevronDown aria-hidden/>
      </button>
      {open &&
        <div className="popup filter-popup" data-end={end || undefined} role="dialog" aria-label={label}>
          <Options label={label} options={all} picked={picked} searchable={searchable} tokens={tokens} maps={maps} self={self} onToggle={toggle} onClose={close}/>
          {picked.length > 0 && <button className="filter-clear" onClick={() => {
            onChange(tokens.filter(it => !own.includes(it)))
            close()
          }}>Clear</button>}
        </div>
      }
    </div>
  )
}

interface OptionsProps {
  label: string
  options: Option[]
  picked: Option[]
  searchable?: boolean
  tokens: Token[]
  maps: Beatmap[]
  self?: User
  onToggle: (option: Option) => void
  onClose: () => void
}

function Options({label, options, picked, searchable, tokens, maps, self, onToggle, onClose}: OptionsProps) {
  const [text, setText] = useState("")
  const [active, setActive] = useState(0)
  const list = useRef<HTMLUListElement>(null)
  const id = `filter-${label.toLowerCase()}`

  // How many beatmaps there would be with only this one, next to all other filters
  const counts = useMemo(() => new Map(options.map(option => {
    const others = tokens.filter(it => option.key === "is" ? !isOption(it, option) : it.key !== option.key)
    return [option, filterMaps(maps, formatQuery([...others, option]), self).length]
  })), [options, tokens, maps, self])
  const countOf = (option: Option) => Array.from(counts).find(([it]) => it.key === option.key && it.value === option.value)?.[1] ?? 0

  // Keep the order while picking, people with nothing to show go last
  const [order] = useState(() => new Map(options.map((option, index) => [
    `${option.key}:${option.value}`,
    searchable ? (picked.includes(option) ? -100000 : option.value === "me" ? -90000 : -countOf(option)) : index
  ])))
  const shown = options
    .filter(it => it.label.toLowerCase().includes(text.trim().toLowerCase()))
    .sort((a, b) => (order.get(`${a.key}:${a.value}`) ?? 0) - (order.get(`${b.key}:${b.value}`) ?? 0))

  useEffect(() => {
    if (!searchable) list.current?.focus()
  }, [searchable])
  useEffect(() => {
    list.current?.children[active]?.scrollIntoView({block: "nearest"})
  }, [active])

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      const count = Math.max(shown.length, 1)
      setActive((active + (event.key === "ArrowDown" ? 1 : count - 1)) % count)
    } else if (event.key === "Enter" || (event.key === " " && !searchable)) {
      if (shown[active]) onToggle(shown[active])
    } else if (event.key === "Escape") {
      onClose()
    } else {
      return
    }
    event.preventDefault()
    event.stopPropagation()
  }

  const activeId = shown[active] && `${id}-${active}`

  return (
    <>
      {searchable &&
        <div className="filter-search">
          <FaMagnifyingGlass aria-hidden/>
          <input autoFocus={focusesByItself()} role="combobox" aria-expanded aria-controls={id} aria-activedescendant={activeId} aria-label={`Find a ${label.toLowerCase()}`}
                 placeholder={`Find a ${label.toLowerCase()}`} autoComplete="off" value={text} onKeyDown={onKeyDown} onChange={event => {
            setText(event.target.value)
            setActive(0)
          }}/>
        </div>
      }
      <ul ref={list} id={id} role="listbox" aria-multiselectable aria-label={label} tabIndex={searchable ? -1 : 0} aria-activedescendant={searchable ? undefined : activeId} onKeyDown={onKeyDown}>
        {shown.map((option, index) => {
          const count = countOf(option)
          const selected = picked.some(it => it.key === option.key && it.value === option.value)
          return (
            <li key={`${option.key}:${option.value}`} id={`${id}-${index}`} role="option" aria-selected={selected} data-key={option.key} data-current={index === active || undefined} data-none={count === 0 || undefined}
                onPointerMove={() => setActive(index)} onClick={() => onToggle(option)}>
              <span className="filter-check" aria-hidden>{selected && <FaCheck/>}</span>
              {option.icon}
              <span className="filter-label">{option.label}</span>
              <span className="filter-count" aria-label={`${count} beatmaps`}>{count}</span>
            </li>
          )
        })}
      </ul>
      {shown.length === 0 && <p className="filter-none">Nobody found.</p>}
    </>
  )
}
