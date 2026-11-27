import {useMemo} from "react";
import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {displayName} from "../utils/users";
import Columns, {Table} from "./Columns";
import type {Timed} from "./speed";
import {duration, extremes, medianOverTime, medianPerMode, speedHistogram, timed} from "./speed";
import {median, rankedDate} from "./stats";
import Delta from "./Delta";
import type {Bucket, TimeRange, Unit} from "./range";
import {inRange} from "./range";
import "./analytics.css";

interface SpeedProps {
  /** In the mode and range */
  scope: Beatmap[]
  /** In the mode, of all time */
  all: Beatmap[]
  /** The buckets of the range */
  list: Bucket[]
  unit: Unit
  /** The range to compare to */
  before?: TimeRange
  mode?: Gamemode
  onOpen: (id: string) => void
}

/** How long it takes from being added to the planner until ranked */
export default function Speed({scope, all, list, unit, before, mode, onOpen}: SpeedProps) {
  const times = useMemo(() => timed(scope), [scope])
  const earlier = useMemo(() => before ? timed(all.filter(it => inRange(rankedDate(it), before))) : undefined, [all, before])
  const over = useMemo(() => medianOverTime(list, times), [list, times])
  const histogram = useMemo(() => speedHistogram(times), [times])
  const perMode = useMemo(() => medianPerMode(times, mode), [times, mode])
  const {fastest, slowest} = useMemo(() => extremes(times), [times])

  if (times.length === 0) return <p className="muted">None of these beatmaps have a known date they were added, so there is nothing to show.</p>
  const most = Math.max(1, ...perMode.map(it => it.days ?? 0))
  const left = scope.length - times.length

  return (
    <>
      <dl className="figures">
        <div><dt>median days to ranked</dt><dd>{median(times.map(it => it.days))}
          <Delta value={median(times.map(it => it.days))} before={earlier && median(earlier.map(it => it.days))} than={before?.label} lowerIsBetter/></dd></div>
        <div><dt>beatmaps with both dates</dt><dd>{times.length}<Delta value={times.length} before={earlier?.length} than={before?.label}/></dd></div>
      </dl>
      {left > 0 && <p className="muted figures-than">{left} without a date they were added are left out</p>}

      <section className="stat">
        <h2>Median days per {unit}</h2>
        <p className="muted">From being added to the planner until ranked, by the {unit} it was ranked. Empty means nothing was ranked.</p>
        <Columns columns={over} unit="days" label={`Median days from added to ranked per ${unit}`}/>
      </section>

      <div className="stat-pair">
        <section className="stat">
          <h2>How long it took</h2>
          <p className="muted">Ranked beatmaps by the time from added to ranked.</p>
          <Columns columns={histogram} unit="ranked" label="Ranked beatmaps by the time it took" height={180}/>
        </section>
        <section className="stat">
          <h2>Per mode</h2>
          <p className="muted">Median days per mode. A hybrid counts in each of its modes.</p>
          <ul className="bars">
            {perMode.map(it => (
              <li key={it.mode} title={`${GAMEMODE_NAMES[it.mode]}: ${it.days ?? "–"} days`}>
                <span className="bars-name"><ModeIcon mode={it.mode}/></span>
                <span className="bars-track"><span style={{width: `${(it.days ?? 0) / most * 100}%`}}/></span>
                <span className="bars-value">{it.days ?? "–"}d</span>
              </li>
            ))}
          </ul>
          <Table head={["Mode", "Median days", "Beatmaps"]} rows={perMode.map(it => [GAMEMODE_NAMES[it.mode], it.days ?? "–", it.count])}/>
        </section>
      </div>

      <div className="stat-pair">
        <Extremes title="Fastest" hint="The quickest from added to ranked." list={fastest} onOpen={onOpen}/>
        <Extremes title="Slowest" hint="The ones that took the longest." list={slowest} onOpen={onOpen}/>
      </div>
    </>
  )
}

function Extremes({title, hint, list, onOpen}: {title: string, hint: string, list: Timed[], onOpen: (id: string) => void}) {
  return (
    <section className="stat">
      <h2>{title}</h2>
      <p className="muted">{hint}</p>
      <ol className="an-list">
        {list.map(({map, days}) => (
          <li key={map.osuId}>
            <span className="map-name">
              <button className="map-open" onClick={() => onOpen(map.osuId)}>{map.title}</button>
              <span className="muted">by {displayName(map.mapper)}, ranked {rankedDate(map)?.toLocaleDateString("en", {month: "short", year: "numeric"})}</span>
            </span>
            <span className="an-days">{duration(days)}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
