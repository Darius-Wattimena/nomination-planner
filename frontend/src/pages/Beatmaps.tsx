import {useMemo, useState} from "react";
import {useSearchParams} from "react-router";
import {FaList, FaPlus, FaTableColumns, FaXmark} from "react-icons/fa6";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {MapCard, MapRow} from "../beatmaps/MapRow";
import Filters from "../beatmaps/Filters";
import {GAMEMODES} from "../Constants";
import type {Beatmap} from "../models/Types";
import {reloadPlanner, usePlanner} from "../resources/planner";
import {useAddBeatmap} from "../beatmaps/AddBeatmap";
import {Freshness, RingLegend} from "../ui/Help";
import PageHeader from "../ui/PageHeader";
import {EmptyState} from "../ui/States";
import {usePhone} from "../utils/phone";
import {filterMaps, MODE_WORDS} from "../utils/query";
import {canEdit, useSession} from "../utils/session";
import {ageInDays, stageOf, STAGES} from "../utils/workflow";
import {readStored, store} from "../utils/storage";

const STEP = 60

interface SavedView {
  name: string
  query: string
}

export default function Beatmaps() {
  const session = useSession()
  const user = session?.user
  const {maps, error} = usePlanner()
  const openAdd = useAddBeatmap()
  const [params, setParams] = useSearchParams()
  const phone = usePhone()
  const [chosen, setLayout] = useState<"board" | "list">(() => readStored<"board" | "list">("boardLayout") ?? "board")
  // Columns need room next to each other
  const layout = phone ? "list" : chosen
  const [saved, setSaved] = useState<SavedView[]>(() => readStored<SavedView[]>("savedViews") ?? [])
  const [naming, setNaming] = useState<string>()
  const [limit, setLimit] = useState(STEP)

  const ownModes = GAMEMODES.filter(mode => user?.gamemodes.some(it => it.gamemode === mode)).map(it => `mode:${MODE_WORDS[it]}`).join(" ")
  const query = params.get("q") ?? ownModes
  const {selected, open, close} = useOpenMap()

  const views: SavedView[] = [
    {name: "Everything", query: ""},
    ...(ownModes ? [{name: "My modes", query: ownModes}] : []),
    ...(canEdit(session) ? [{name: "I'm nominating", query: "is:mine"}] : []),
    {name: "Needs nominators", query: `${ownModes} is:open`.trim()},
    {name: "Gone quiet", query: `${ownModes} is:stale`.trim()}
  ]

  function update(changes: {q: string}) {
    const next = new URLSearchParams(params)
    next.set("q", changes.q)
    setParams(next, {replace: true})
  }

  function saveViews(next: SavedView[]) {
    setSaved(next)
    store("savedViews", next)
  }

  const found = useMemo(() => filterMaps(maps ?? [], query, user), [maps, query, user])
  const columns = STAGES.map(stage => ({
    ...stage,
    maps: found.filter(it => stageOf(it) === stage.id).sort((a, b) => ageInDays(a) - ageInDays(b))
  }))
  const ordered = columns.flatMap(it => it.maps)

  // Start with a short list again for another search or layout
  const [limitFor, setLimitFor] = useState(`${query}|${layout}`)
  if (limitFor !== `${query}|${layout}`) {
    setLimitFor(`${query}|${layout}`)
    setLimit(STEP)
  }
  const ids = (layout === "list" ? ordered.slice(0, limit) : columns.flatMap(it => it.maps.slice(0, limit))).map(it => it.osuId)

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
            <span key={view.name} className="view view-saved" data-active={view.query === query || undefined}>
              <button aria-pressed={view.query === query} onClick={() => update({q: view.query})}>{view.name}</button>
              <button aria-label={`Delete view ${view.name}`} onClick={() => saveViews(saved.filter(it => it !== view))}><FaXmark/></button>
            </span>
          ))}
          {!isSaved && (naming === undefined
            ? <button className="link" onClick={() => setNaming("")}>Save view</button>
            : <form onSubmit={event => {
                event.preventDefault()
                const name = naming.trim()
                if (name) saveViews([...saved.filter(it => it.name !== name), {name, query}])
                setNaming(undefined)
              }}>
                <input id="view-name" autoFocus aria-label="Name of the view" placeholder="Name, then Enter" value={naming} onChange={event => setNaming(event.target.value)} onBlur={() => setNaming(undefined)}/>
              </form>
          )}
          <span className="views-end"><RingLegend/></span>
        </div>
        <Filters query={query} onChange={q => update({q})} maps={maps ?? []} stages={layout === "list"}/>

        {error && <p className="alert alert-error" role="alert">Could not refresh the planner. <button className="link" onClick={() => void reloadPlanner()}>Try again</button></p>}
        {!maps && !error && <ul className="map-list" aria-busy aria-label="Loading">{[0, 1, 2, 3, 4].map(it => <li key={it} className="map-row skeleton"/>)}</ul>}
        {maps && found.length === 0 &&
          <EmptyState title="Nothing matches">
            <p className="muted">No beatmap in progress fits this search.</p>
            {query && <button className="button" onClick={() => update({q: ""})}>Show everything</button>}
          </EmptyState>
        }

        {found.length > 0 && layout === "board" &&
          <div className="board" role="group" aria-label="Stages">
            {columns.map(column => (
              <section key={column.id} className={`column column-${column.id}`}>
                <header><h2>{column.label}</h2><span className="count">{column.maps.length}</span></header>
                <p className="muted">{column.hint}</p>
                <ul>
                  {column.maps.slice(0, limit).map(it => <MapCard key={it.osuId} beatmap={it} selected={selected === it.osuId} onOpen={() => open(it.osuId)}/>)}
                </ul>
                {column.maps.length > limit && <button className="button" onClick={() => setLimit(limit + STEP)}>Show more</button>}
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
                  <ul className="map-list">{shown.map((it: Beatmap) => <MapRow key={it.osuId} beatmap={it} selected={selected === it.osuId} onOpen={() => open(it.osuId)}/>)}</ul>
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
