import {useMemo} from "react";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Gamemode} from "../models/Types";
import {usePlanner} from "../resources/planner";
import Cover from "../ui/Cover";
import ModeIcon from "../ui/ModeIcon";
import {Loading} from "../ui/States";
import {displayName} from "../utils/users";
import {ageLabel} from "../utils/workflow";
import {ModeBars} from "./Charts";
import {Table} from "./Columns";
import {AGE_BUCKETS, longestWaiting, ofMode, stages as stagesOf, supply} from "./pipeline";
import "./analytics.css";

/** How the beatmaps in progress move along, from the live planner */
export default function Pipeline({mode, onOpen}: {mode?: Gamemode, onOpen: (id: string) => void}) {
  const {maps, nominators} = usePlanner()
  const own = useMemo(() => ofMode(maps ?? [], mode), [maps, mode])
  const stages = useMemo(() => stagesOf(own), [own])
  const modes = useMemo(() => GAMEMODES.filter(it => !mode || it === mode), [mode])
  const supplies = useMemo(() => modes.map(it => supply(maps ?? [], nominators, it)), [maps, nominators, modes])
  const waiting = useMemo(() => longestWaiting(own, mode), [own, mode])
  if (!maps) return <Loading/>

  const mostAge = Math.max(1, ...stages.map(it => it.count))
  const mostSupply = Math.max(1, ...supplies.flatMap(it => [it.open, it.free ?? 0]))

  return (
    <>
      <section className="stat">
        <h2>Where beatmaps are</h2>
        <p className="muted">Beatmaps in progress per stage, with the median days since their last update. A hybrid counts in each of its modes.</p>
        <ModeBars label="Beatmaps in progress per stage" rows={stages.map(it => ({
          key: it.id, name: it.label, label: <span title={it.hint}>{it.label}</span>, total: it.count,
          parts: modes.map(own => ({mode: own, value: it.modes[own]})),
          extra: it.days !== undefined && <span className="muted"> {it.days}d</span>
        }))}/>
      </section>

      <div className="stat-pair">
        <section className="stat">
          <h2>How long they have waited</h2>
          <p className="muted">Time since the last update, per stage. Darker is longer.</p>
          <ul className="bars bars-wide" aria-label="Time since the last update per stage">
            {stages.map(it => (
              <li key={it.id}>
                <span className="bars-name an-small">{it.label}</span>
                <span className="bars-track an-stack" role="img" style={{width: `${it.count / mostAge * 100}%`}}
                      aria-label={`${it.label}: ${AGE_BUCKETS.map((bucket, index) => `${it.ages[index]} ${bucket.label.toLowerCase()}`).join(", ")}`}>
                  {it.ages.map((count, index) => count > 0 && <span key={index} className="an-age" data-age={index} style={{flexGrow: count}}/>)}
                </span>
                <span className="bars-value">{it.days !== undefined ? `${it.days}d` : "–"}</span>
              </li>
            ))}
          </ul>
          <ul className="an-legend" aria-hidden>
            {AGE_BUCKETS.map((it, index) => <li key={it.short}><span className="legend-dot an-age" data-age={index}/>{it.short}</li>)}
          </ul>
          <Table head={["Stage", ...AGE_BUCKETS.map(it => it.short), "Median days"]} rows={stages.map(it => [it.label, ...it.ages, it.days ?? "–"])}/>
        </section>

        <section className="stat">
          <h2>Supply and demand</h2>
          <p className="muted">Open spots against nominators of the mode with nothing planned.</p>
          <ul className="an-supply">
            {supplies.map(it => (
              <li key={it.mode}>
                <span className="bars-name"><ModeIcon mode={it.mode}/></span>
                <span className="an-pair">
                  <span className="bars-track" role="img" aria-label={`${GAMEMODE_NAMES[it.mode]}: ${it.open} open spots`}><span style={{width: `${it.open / mostSupply * 100}%`}}/></span>
                  <span className="bars-track an-free" role="img" aria-label={`${GAMEMODE_NAMES[it.mode]}: ${it.free ?? "unknown"} nominators with nothing planned`}><span style={{width: `${(it.free ?? 0) / mostSupply * 100}%`}}/></span>
                </span>
                <span className="bars-value an-pair"><span>{it.open}</span><span>{it.free ?? "…"}</span></span>
              </li>
            ))}
          </ul>
          <ul className="an-legend" aria-hidden>
            <li><span className="legend-dot an-open"/>Open spots</li>
            <li><span className="legend-dot an-free"/>Nothing planned</li>
          </ul>
          <Table head={["Mode", "Open spots", "Nothing planned", "Nominators"]} rows={supplies.map(it => [GAMEMODE_NAMES[it.mode], it.open, it.free ?? "…", it.team ?? "…"])}/>
        </section>
      </div>

      <section className="stat">
        <h2>Waiting longest for a nominator</h2>
        <p className="muted">Beatmaps with an open spot, the longest since their last update first.</p>
        {waiting.length === 0 && <p className="muted">Every beatmap has its nominators.</p>}
        <ul className="debuts">
          {waiting.map(({map, mode: open, partner}) => (
            <li key={map.osuId} className="debut">
              <span className="map-thumb"><Cover id={map.osuId} size="card"/></span>
              <span className="map-name">
                <button className="map-open" onClick={() => onOpen(map.osuId)}>{map.artist ? `${map.artist} - ` : ""}{map.title}</button>
                <span className="muted">by {displayName(map.mapper)}</span>
                <span className="debut-by"><ModeIcon mode={open}/>{partner ? `With ${displayName(partner)}` : "No nominators yet"}</span>
              </span>
              <span className="age">{ageLabel(map)}</span>
            </li>
          ))}
        </ul>
      </section>
    </>
  )
}
