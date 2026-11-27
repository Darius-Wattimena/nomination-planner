import {useEffect, useMemo, useState} from "react";
import {useSearchParams} from "react-router";
import {FaList, FaPlus, FaTableColumns, FaXmark} from "react-icons/fa6";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapCard, MapRow} from "../beatmaps/MapRow";
import Filters from "../beatmaps/Filters";
import type {Beatmap} from "../models/Types";
import {usePlanner} from "../resources/planner";
import {useAddBeatmap} from "../beatmaps/useAddBeatmap";
import Freshness from "../ui/Freshness";
import {RingLegend} from "../ui/Help";
import PageHeader from "../ui/PageHeader";
import {EmptyState, PlannerStatus} from "../ui/States";
import {focusesByItself, usePhone} from "../utils/phone";
import {filterMaps, MODE_WORDS} from "../utils/query";
import {canEdit, useSession} from "../utils/session";
import {modesOf} from "../utils/users";
import {ageInDays, hiddenAsOld, LABELS, OLD_DAYS, stageOf, STAGES} from "../utils/workflow";
import {readStored, store, storeFirst} from "../utils/storage";
import {useToast} from "../ui/useToast";

const STEP = 60

interface SavedView {
  name: string
  query: string
}

/** Stored by an older version or changed by hand, what isn't a view is left out */
function readViews(): SavedView[] {
  const stored = readStored<unknown>("savedViews")
  if (!Array.isArray(stored)) return []
  return stored.filter((it: unknown): it is SavedView => {
    const view = it as Partial<SavedView> | null
    return typeof view?.name === "string" && view.name.trim() !== "" && typeof view.query === "string"
  })
}

export default function Beatmaps() {
  const session = useSession()
  const user = session?.user
  const {maps} = usePlanner()
  const openAdd = useAddBeatmap()
  const [params, setParams] = useSearchParams()
  const phone = usePhone()
  const [chosen, setLayout] = useState<"board" | "list">(() => readStored<"board" | "list">("boardLayout") ?? "board")
  // Columns need room next to each other
  const layout = phone ? "list" : chosen
  const [saved, setSaved] = useState<SavedView[]>(readViews)
  const toast = useToast()
  const [naming, setNaming] = useState<string>()
  // The list grows as a whole, every column by itself
  const [limit, setLimit] = useState(STEP)
  const [columnLimits, setColumnLimits] = useState<Record<string, number>>({})
  const limitOf = (column: string) => columnLimits[column] ?? STEP

  const ownModes = modesOf(user).map(it => `mode:${MODE_WORDS[it]}`).join(" ")
  const query = params.get("q") ?? ownModes
  const older = params.get("older") === "show"
  const {selected, open, close} = useOpenMap()

  const views: SavedView[] = [
    {name: "Everything", query: ""},
    ...(ownModes ? [{name: "My modes", query: ownModes}] : []),
    ...(canEdit(session) ? [{name: "I'm nominating", query: "is:mine"}] : []),
    {name: LABELS.open, query: `${ownModes} is:open`.trim()},
    {name: LABELS.stale, query: `${ownModes} is:stale`.trim()}
  ]

  function update(changes: {q?: string, older?: boolean}) {
    const next = new URLSearchParams(params)
    if (changes.q !== undefined) next.set("q", changes.q)
    if (changes.older !== undefined) {
      if (changes.older) next.set("older", "show")
      else next.delete("older")
    }
    setParams(next, {replace: true})
  }

  useEffect(() => {
    storeFirst("savedViews", saved)
  }, [saved])

  function deleteView(view: SavedView) {
    const index = saved.indexOf(view)
    setSaved(saved.filter(it => it !== view))
    toast(`Deleted the view ${view.name}`, {undo: () => setSaved(now => now.some(it => it.name === view.name) ? now : [...now.slice(0, index), view, ...now.slice(index)])})
  }

  const matching = useMemo(() => filterMaps(maps ?? [], query, user), [maps, query, user])
  const old = useMemo(() => new Set(matching.filter(it => hiddenAsOld(it, user?.osuId))), [matching, user])
  const found = useMemo(() => older ? matching : matching.filter(it => !old.has(it)), [matching, old, older])
  const columns = useMemo(() => STAGES.map(stage => ({
    ...stage,
    maps: found.filter(it => stageOf(it) === stage.id).sort((a, b) => ageInDays(a) - ageInDays(b))
  })), [found])
  const ordered = useMemo(() => columns.flatMap(it => it.maps), [columns])

  // Start with a short list again for another search or layout
  const [limitFor, setLimitFor] = useState(`${query}|${layout}`)
  if (limitFor !== `${query}|${layout}`) {
    setLimitFor(`${query}|${layout}`)
    setLimit(STEP)
    setColumnLimits({})
  }
  const ids = (layout === "list" ? ordered.slice(0, limit) : columns.flatMap(it => it.maps.slice(0, limitOf(it.id)))).map(it => it.osuId)

  const isSaved = [...views, ...saved].some(it => it.query === query)

  return (
    <div className="page">
        <PageHeader title="Beatmaps" subtitle={maps ? <>{found.length} of {maps.length} in progress <Freshness/></> : " "}>
          <div className="button-row">
            {!phone && <div className="segmented" role="group" aria-label="Layout">
              {(["board", "list"] as const).map(it => (
                <button key={it} aria-pressed={layout === it} onClick={() => {
                  setLayout(it)
                  store("boardLayout", it)
                }}>{it === "board" ? <FaTableColumns/> : <FaList/>}{it === "board" ? "Columns" : "List"}</button>
              ))}
            </div>}
            {canEdit(session) && <button className="button button-primary" onClick={() => openAdd()}><FaPlus/>Add beatmap</button>}
          </div>
        </PageHeader>

        <div className="views" role="group" aria-label="Views">
          {views.map(view => <button key={view.name} className="view" aria-pressed={view.query === query} onClick={() => update({q: view.query})}>{view.name}</button>)}
          {saved.map(view => (
            <span key={`saved:${view.name}`} className="view view-saved" data-active={view.query === query || undefined}>
              <button aria-pressed={view.query === query} onClick={() => update({q: view.query})}>{view.name}</button>
              <button aria-label={`Delete view ${view.name}`} onClick={() => deleteView(view)}><FaXmark/></button>
            </span>
          ))}
          {!isSaved && (naming === undefined
            ? <button className="link" onClick={() => setNaming("")}>Save view</button>
            : <form onSubmit={event => {
                event.preventDefault()
                const name = naming.trim()
                if (name) setSaved([...saved.filter(it => it.name !== name), {name, query}])
                setNaming(undefined)
              }}>
                <input id="view-name" autoFocus={focusesByItself()} aria-label="Name of the view" placeholder="Name, then Enter" value={naming} onChange={event => setNaming(event.target.value)} onBlur={() => setNaming(undefined)}/>
              </form>
          )}
          <span className="views-end"><RingLegend/></span>
        </div>
        <Filters query={query} onChange={q => update({q})} maps={maps ?? []} stages={layout === "list"}/>
        {old.size > 0 &&
          <p className="older-note muted">
            {older
              ? <>Including {old.size} {old.size === 1 ? "beatmap" : "beatmaps"} nobody changed in the last {OLD_DAYS} days.</>
              : <>{old.size} {old.size === 1 ? "beatmap" : "beatmaps"} nobody changed in the last {OLD_DAYS} days {old.size === 1 ? "is" : "are"} left out. Your own stay.</>}
            {" "}<button className="link" onClick={() => update({older: !older})}>{older ? "Leave them out" : "Include them"}</button>
          </p>
        }

        <PlannerStatus rows={5}/>
        {maps && found.length === 0 &&
          <EmptyState title="Nothing matches">
            <p className="muted">No beatmap in progress fits this search.</p>
            {query && <button className="button" onClick={() => update({q: ""})}>Show everything</button>}
            {old.size > 0 && <button className="button" onClick={() => update({older: true})}>Include older ones</button>}
          </EmptyState>
        }

        {found.length > 0 && layout === "board" &&
          <div className="board" role="group" aria-label="Stages">
            {columns.map(column => (
              <section key={column.id} className={`column column-${column.id}`}>
                <header><h2>{column.label}</h2><span className="count">{column.maps.length}</span></header>
                <p className="muted">{column.hint}</p>
                <ul>
                  {column.maps.slice(0, limitOf(column.id)).map(it => <MapCard key={it.osuId} beatmap={it} selected={selected === it.osuId} onOpen={open}/>)}
                </ul>
                {column.maps.length > limitOf(column.id) && <button className="button" onClick={() => setColumnLimits({...columnLimits, [column.id]: limitOf(column.id) + STEP})}>Show more</button>}
              </section>
            ))}
          </div>
        }
        {found.length > 0 && layout === "list" &&
          <>
            {columns.map(column => {
              const before = columns.slice(0, columns.indexOf(column)).reduce((sum, it) => sum + it.maps.length, 0)
              const shown = column.maps.slice(0, Math.max(0, limit - before))
              return shown.length === 0 ? null : (
                <section key={column.id} className="map-group">
                  <header><h2>{column.label}</h2><span className="count">{column.maps.length}</span></header>
                  <ul className="map-list">{shown.map((it: Beatmap) => <MapRow key={it.osuId} beatmap={it} selected={selected === it.osuId} onOpen={open}/>)}</ul>
                </section>
              )
            })}
            {ordered.length > limit && <button className="button" onClick={() => setLimit(limit + STEP)}>Show more</button>}
          </>
        }
      <MapView id={selected} ids={ids} onOpen={open} onClose={close}/>
    </div>
  )
}
