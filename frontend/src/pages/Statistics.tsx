import {useMemo} from "react";
import {useSearchParams} from "react-router";
import {useOpenMap} from "../beatmaps/useOpenMap";
import MapView from "../beatmaps/MapView";
import {GAMEMODE_NAMES} from "../Constants";
import {useRanked} from "../resources/ranked";
import Columns, {Table} from "../stats/Columns";
import Constellation from "../stats/Constellation";
import {combinations, debuts, inLastYear, inYear, network, nominatorsOf, overTime, perMonth, rankedDate, summarize, yearBefore} from "../stats/stats";
import {Figures, Highlights, YearTable} from "../stats/Years";
import {modeParam} from "../utils/query";
import {boards, scores} from "../stats/boards";
import Leaderboards from "../stats/Leaderboards";
import Cover from "../ui/Cover";
import {People} from "../ui/Person";
import ModeIcon, {ModeSwitch} from "../ui/ModeIcon";
import PageHeader from "../ui/PageHeader";
import {EmptyState, Loading} from "../ui/States";

const TABS = [{id: "overview", label: "Overview"}, {id: "constellation", label: "Constellation"}, {id: "leaderboards", label: "Leaderboards"}]

export default function Statistics() {
  const {maps, error, retry} = useRanked()
  const [params, setParams] = useSearchParams()
  const {selected, open, close} = useOpenMap()
  const tab = TABS.find(it => it.id === params.get("view"))?.id ?? "overview"
  const chosen = modeParam(params.get("mode"))
  const mode = chosen === "all" ? undefined : chosen
  const years = useMemo(() => Array.from(new Set((maps ?? []).map(it => rankedDate(it)?.getFullYear()).filter((it): it is number => !!it))).sort((a, b) => b - a), [maps])
  const period = params.get("period") ?? "recent"
  const year = /^[0-9]{4}$/.test(period) ? Number(period) : undefined
  const lastYear = !year && period !== "all"
  const thisYear = new Date().getFullYear()

  function update(changes: {view?: string, period?: string, mode?: string}) {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([key, value]) => ["overview", "recent"].includes(value) || (key === "mode" && value === "all") ? next.delete(key) : next.set(key, value))
    setParams(next, {replace: true})
  }

  const ofMode = useMemo(() => (maps ?? []).filter(it => !mode || it.gamemodes.some(own => own.gamemode === mode)), [maps, mode])
  const scope = useMemo(() => year ? ofMode.filter(it => inYear(it, year)) : lastYear ? ofMode.filter(it => inLastYear(it)) : ofMode, [ofMode, lastYear, year])
  const stars = useMemo(() => network(scope, mode), [scope, mode])
  const time = useMemo(() => year
    ? {unit: "month" as const, columns: perMonth(scope, year * 12, year * 12 + (year === thisYear ? new Date().getMonth() : 11)).map(it => ({...it, short: it.label.slice(0, 3)}))}
    : overTime(ofMode, lastYear), [ofMode, scope, lastYear, year, thisYear])
  const combos = useMemo(() => combinations(scope), [scope])
  // Whether it is a debut depends on everything the mapper ever ranked
  const everyDebut = useMemo(() => debuts(maps ?? []), [maps])
  const newMappers = useMemo(() => everyDebut.filter(it => scope.includes(it)), [everyDebut, scope])

  const firsts = useMemo(() => new Set(everyDebut.map(it => it.osuId)), [everyDebut])
  const ranking = useMemo(() => boards(scores(scope, firsts, mode)), [scope, firsts, mode])

  const summary = summarize(scope, firsts, mode)
  const before = year && years.includes(year - 1) ? summarize(yearBefore(ofMode, year), firsts, mode) : undefined
  const hybrids = scope.filter(it => it.gamemodes.length > 1).length
  const undated = ofMode.filter(it => !rankedDate(it)).length
  const what = mode ? GAMEMODE_NAMES[mode] : "all modes"

  return (
    <div className="page">
      <PageHeader title="Statistics" subtitle="Beatmaps that were ranked through the planner, not all of osu!">
        <div className="button-row">
          <ModeSwitch selected={mode} onChange={it => update({mode: it ?? "all"})}/>
          <select id="period" aria-label="Period" value={period} onChange={event => update({period: event.target.value})}>
            <option value="recent">Last 12 months</option>
            <option value="all">All time</option>
            <optgroup label="Year">
              {(year && !years.includes(year) ? [year, ...years] : years).map(it => <option key={it} value={it}>{it}</option>)}
            </optgroup>
          </select>
        </div>
      </PageHeader>

      <nav className="tabs" aria-label="Statistics">
        {TABS.map(it => <button key={it.id} aria-current={tab === it.id ? "page" : undefined} onClick={() => update({view: it.id})}>{it.label}</button>)}
      </nav>

      {error && <p className="alert alert-error" role="alert">Could not load the statistics. <button className="link" onClick={retry}>Try again</button></p>}
      {!maps && !error && <Loading/>}
      {maps && scope.length === 0 && <EmptyState title="Nothing ranked here yet"><p className="muted">Try another mode or period.</p></EmptyState>}
      {scope.length > 0 &&
        <>
          {tab === "overview" && <>
          <Figures summary={summary} before={before} than={year === thisYear ? `${year - 1} until the same day` : String(year && year - 1)}/>

          <section className="stat">
            <h2>Ranked per {time.unit}</h2>
            <p className="muted">{mode ? what : "All modes, a hybrid counts once"}{undated > 0 && period === "all" && `. ${undated} without a known date are left out`}</p>
            <Columns columns={time.columns} unit="ranked" label={`Ranked beatmaps per ${time.unit}, ${what}`} unfinished={!year || year === thisYear}/>
          </section>
          {year && <Highlights maps={scope} all={ofMode} year={year} mode={mode}/>}
          {period === "all" && years.length > 1 && <YearTable maps={ofMode} firsts={firsts} years={years} mode={mode} onPick={it => {
            update({period: String(it)})
            window.scrollTo({top: 0})
          }}/>}

          </>}
          {tab === "constellation" && <section className="stat">
            <h2>Constellation</h2>
            <p className="muted">Who ranked beatmaps together, grouped by mode</p>
            <Constellation key={`${mode}-${period}`} data={stars}/>
          </section>}

          {tab === "leaderboards" && <section className="stat">
            <h2>Leaderboards</h2>
            <p className="muted">Different ways to stand out, {year ? `in ${year}` : lastYear ? "over the last 12 months" : "of all time"}{mode && ` for ${what}`}</p>
            <Leaderboards boards={ranking}/>
          </section>}

          {tab === "overview" && <div className="stat-pair">
            {newMappers.length > 0 &&
              <section className="stat">
                <h2>First ranked beatmaps</h2>
                <p className="muted">Mappers who got their first one through the planner</p>
                <ul className="debuts">
                  {newMappers.slice(0, 8).map(map => (
                    <li key={map.osuId} className="debut">
                      <span className="map-thumb"><Cover id={map.osuId} size="card"/></span>
                      <span className="map-name"><button className="map-open" onClick={() => open(map.osuId)}>{map.mapper.username}</button><span className="muted">{map.title}</span>
                        <span className="debut-by"><People users={nominatorsOf(map)}/><span>{names(nominatorsOf(map).map(it => it.username))}</span></span>
                      </span>
                      <span className="age">{rankedDate(map)?.toLocaleDateString("en", {month: "short", year: "numeric"})}</span>
                    </li>
                  ))}
                </ul>
              </section>
            }
            {combos.length > 0 &&
              <section className="stat">
                <h2>Hybrids</h2>
                <p className="muted">{hybrids} of {scope.length} beatmaps have more than one mode</p>
                <ul className="bars">
                  {combos.slice(0, 7).map(it => (
                    <li key={it.key} title={`${it.label}: ${it.count}`}>
                      <span className="bars-name" role="img" aria-label={it.label}>{it.modes.map(own => <ModeIcon key={own} mode={own}/>)}</span>
                      <span className="bars-track"><span style={{width: `${it.count / combos[0].count * 100}%`}}/></span>
                      <span className="bars-value">{it.count}</span>
                    </li>
                  ))}
                </ul>
                <Table head={["Modes", "Ranked"]} rows={combos.map(it => [it.label, it.count])}/>
              </section>
            }
          </div>}
        </>
      }
      <MapView id={selected} ids={newMappers.slice(0, 8).map(it => it.osuId)} onOpen={open} onClose={close}/>
    </div>
  )
}

function names(all: string[]) {
  if (all.length > 3) return `${all.slice(0, 2).join(", ")} and ${all.length - 2} others`
  return all.length > 1 ? `${all.slice(0, -1).join(", ")} and ${all[all.length - 1]}` : all.join()
}
