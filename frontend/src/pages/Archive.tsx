import {useEffect, useRef, useState} from "react";
import {NavLink, useSearchParams} from "react-router";
import {FaMagnifyingGlass, FaXmark} from "react-icons/fa6";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import Pagination from "../beatmaps/Pagination";
import {GAMEMODES} from "../Constants";
import type {Beatmap, BeatmapFilter, BeatmapPage, PageLimit} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import Cover from "../ui/Cover";
import {ModePicker} from "../ui/ModeIcon";
import PageHeader from "../ui/PageHeader";
import {Rings} from "../ui/Ring";
import {EmptyState, Loading} from "../ui/States";
import {useSession} from "../utils/session";
import {toDate} from "../utils/workflow";

/** What is done. Too much to keep in memory, so the server does the searching. */
export default function Archive({page}: {page: BeatmapPage}) {
  const user = useSession()?.user
  const [params, setParams] = useSearchParams()
  // Several changes may follow each other before the page shows them
  const latest = useRef(params)
  useEffect(() => {
    latest.current = params
  }, [params])

  const number = Number(params.get("page"))
  const pageNumber = Number.isInteger(number) && number > 1 ? number : 1
  const limit: PageLimit = params.get("limit") === "FIFTY" ? "FIFTY" : params.get("limit") === "TEN" ? "TEN" : "TWENTY"
  const modes = GAMEMODES.filter(it => params.get("mode")?.split(",").includes(it))
  const bn = params.get("bn") ?? undefined
  const {selected, open, close} = useOpenMap()
  const filter: BeatmapFilter = {page, search: params.get("q") ?? "", gamemodes: modes, nominators: bn ? [bn] : [], status: [], missingNominator: []}

  const total = useQuery<number>(Api.countBeatmaps(filter))
  const maps = useQuery<Beatmap[]>(Api.fetchBeatmaps(filter, pageNumber, limit))
  const count = total.data ?? 0

  function update(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(latest.current)
    if (!("page" in changes)) next.delete("page")
    Object.entries(changes).forEach(([key, value]) => value ? next.set(key, value) : next.delete(key))
    latest.current = next
    setParams(next, {replace: !("page" in changes)})
  }

  function reload() {
    total.reload()
    maps.reload()
  }

  return (
    <div className="page">
        <PageHeader title="Archive" subtitle={total.data !== undefined ? `${count} ${page === "RANKED" ? "ranked" : "in the graveyard"}` : " "}>
          <nav className="segmented" aria-label="Archive">
            <NavLink to="/ranked">Ranked</NavLink>
            <NavLink to="/graveyard">Graveyard</NavLink>
          </nav>
        </PageHeader>
        <div className="archive-filters">
          <Search value={filter.search} onChange={q => update({q})}/>
          <ModePicker selected={modes} onChange={it => update({mode: it.join(",")})} label="Game modes"/>
          {user && bn !== user.osuId && <button className="chip" onClick={() => update({bn: user.osuId})}>Nominated by me</button>}
          {bn && <button className="chip" aria-pressed onClick={() => update({bn: undefined})}>{bn === user?.osuId ? "Nominated by me" : `Nominator ${bn}`}<FaXmark/></button>}
        </div>
        {maps.error && <p className="alert alert-error" role="alert">Could not load the archive. <button className="link" onClick={reload}>Try again</button></p>}
        {!maps.data && maps.loading && <Loading/>}
        {maps.data?.length === 0 && !maps.loading && <EmptyState title="Nothing found"><p className="muted">Try another search.</p></EmptyState>}
        {maps.data && maps.data.length > 0 &&
          <>
            <ul className="gallery" aria-busy={maps.loading}>
              {maps.data.map(it => {
                const ranked = toDate(it.dateRanked)
                return (
                  <li key={it.osuId} className="gallery-item cover-card" aria-current={selected === it.osuId || undefined}>
                    <span className="map-thumb"><Cover id={it.osuId} size="card"/></span>
                    <span className="cover-text">
                      <span>{it.artist}</span>
                      <button className="map-open" onClick={() => open(it.osuId)}>{it.title}</button>
                      <em>Mapped by {it.mapper.username}</em>
                    </span>
                    <span className="map-foot"><Rings beatmap={it}/>{ranked && <span className="age">{ranked.toLocaleDateString()}</span>}</span>
                  </li>
                )
              })}
            </ul>
            <Pagination total={count} pageNumber={pageNumber} limit={limit}
                        onPage={it => update({page: it > 1 ? String(it) : undefined})}
                        onLimit={it => update({limit: it})}/>
          </>
        }
      <MapView id={selected} ids={maps.data?.map(it => it.osuId)} onOpen={open} onClose={close} onChanged={reload}/>
    </div>
  )
}

function Search({value, onChange}: {value: string, onChange: (value: string) => void}) {
  const [text, setText] = useState(value)
  const timer = useRef<number>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])

  return (
    <label className="search">
      <FaMagnifyingGlass aria-hidden/>
      <input id="archive-search" type="search" aria-label="Search the archive" placeholder="Search artist, title or mapper" value={text} onChange={event => {
        const next = event.target.value
        setText(next)
        window.clearTimeout(timer.current)
        timer.current = window.setTimeout(() => onChange(next), 300)
      }}/>
    </label>
  )
}
