import {useMemo} from "react";
import {Link} from "react-router";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {Avatar} from "../ui/Person";
import type {Summary} from "./stats";
import {inYear, network, nominatorsOf, perMonth, rankedDate, summarize} from "./stats";

interface FiguresProps {
  summary: Summary
  before?: Summary
  /** What it is compared to, like "2025" */
  than?: string
}

export function Figures({summary, before, than}: FiguresProps) {
  const figures: [keyof Summary, string, string][] = [
    ["ranked", "ranked", "ranked"],
    ["mappers", "mapper", "mappers"],
    ["nominators", "nominator", "nominators"],
    ["firsts", "ranked for the first time", "ranked for the first time"],
    ["hybrids", "hybrid", "hybrids"]
  ]
  return (
    <>
      <dl className="figures">
        {figures.map(([key, one, many]) => {
          const change = before && summary[key] - before[key]
          return (
            <div key={key}>
              <dt>{summary[key] === 1 ? one : many}</dt>
              <dd>{summary[key]}{change !== undefined && change !== 0 && <span className="change">{change > 0 ? "+" : "−"}{Math.abs(change)}</span>}</dd>
            </div>
          )
        })}
      </dl>
      {before && <p className="muted figures-than">Compared to {than}</p>}
    </>
  )
}

/** What is worth remembering about a year */
export function Highlights({maps, all, year, mode}: {maps: Beatmap[], all: Beatmap[], year: number, mode?: Gamemode}) {
  const months = perMonth(maps, year * 12, year * 12 + 11)
  const busiest = months.reduce((best, it) => it.value > best.value ? it : best, months[0])
  const {links, people} = useMemo(() => network(maps, mode), [maps, mode])
  const pair = links[0]
  const userOf = (id: string) => people.find(it => it.user.osuId === id)!.user

  // Whoever had nothing ranked in the years before
  const earlier = useMemo(() => new Set(all.filter(it => rankedDate(it) && rankedDate(it)!.getFullYear() < year)
    .flatMap(it => nominatorsOf(it, mode)).map(it => it.osuId)), [all, year, mode])
  const fresh = earlier.size > 0 ? people.filter(it => !earlier.has(it.user.osuId)).map(it => it.user) : []
  const pairings = links.length

  return (
    <section className="stat">
      <h2>{year} in short</h2>
      <ul className="highlights">
        <li><span className="muted">Most active month</span><strong>{busiest.label.split(" ")[0]}</strong><span>{busiest.value} ranked</span></li>
        {pair &&
          <li>
            <span className="muted">Most often together</span>
            <strong>{userOf(pair.a).username} and {userOf(pair.b).username}</strong>
            <span>{pair.count} ranked</span>
          </li>
        }
        {pairings > 0 && <li><span className="muted">Different pairs</span><strong>{pairings}</strong><span>ranked something together</span></li>}
        {fresh.length > 0 &&
          <li>
            <span className="muted">First ranked nomination</span>
            <strong>{fresh.length} {fresh.length === 1 ? "nominator" : "nominators"}</strong>
            <span className="highlight-people">{fresh.slice(0, 8).map(it => <Link key={it.osuId} to={`/team?user=${it.osuId}`} aria-label={it.username} title={it.username}><Avatar user={it}/></Link>)}{fresh.length > 8 && `+${fresh.length - 8}`}</span>
          </li>
        }
      </ul>
    </section>
  )
}

interface YearTableProps {
  maps: Beatmap[]
  firsts: Set<string>
  years: number[]
  mode?: Gamemode
  onPick: (year: number) => void
}

/** All years below each other, to compare them */
export function YearTable({maps, firsts, years, mode, onPick}: YearTableProps) {
  const rows = years.map(year => ({year, own: maps.filter(it => inYear(it, year))})).map(it => ({...it, summary: summarize(it.own, firsts, mode)}))
  const most = Math.max(...rows.map(it => it.summary.ranked), 1)
  const current = new Date().getFullYear()

  return (
    <section className="stat">
      <h2>Year by year</h2>
      <p className="muted">Pick a year to see all of it</p>
      <div className="year-table">
        <table>
          <thead>
            <tr>
              <th scope="col">Year</th><th scope="col" className="year-bar">Ranked</th>
              {!mode && GAMEMODES.map(it => <th key={it} scope="col"><span role="img" aria-label={GAMEMODE_NAMES[it]} title={GAMEMODE_NAMES[it]}><ModeIcon mode={it}/></span></th>)}
              <th scope="col">Hybrids</th><th scope="col">Mappers</th><th scope="col">First time</th><th scope="col">Nominators</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({year, own, summary}) => (
              <tr key={year} onClick={() => onPick(year)}>
                <th scope="row"><button className="link" onClick={event => {
                  event.stopPropagation()
                  onPick(year)
                }}>{year}</button>{year === current && <span className="muted"> so far</span>}</th>
                <td className="year-bar"><span className="year-value">{summary.ranked}</span><span className="bars-track"><span style={{width: `${summary.ranked / most * 100}%`}}/></span></td>
                {!mode && GAMEMODES.map(it => <td key={it}>{own.filter(map => map.gamemodes.some(own => own.gamemode === it)).length}</td>)}
                <td>{summary.hybrids}</td><td>{summary.mappers}</td><td>{summary.firsts}</td><td>{summary.nominators}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
