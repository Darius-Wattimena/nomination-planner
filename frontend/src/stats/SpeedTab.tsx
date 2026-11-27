import {useMemo} from "react";
import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {displayName} from "../utils/users";
import {MODE_WORDS} from "../utils/query";
import Columns, {Table} from "./Columns";
import type {Timed} from "./speed";
import {duration, extremes, FEWEST, medianPerMode, modeTrends, singleVsHybrid, speedHistogram, spreadOverTime, timed, withinOverTime, withinShare} from "./speed";
import {median, rankedDate} from "./stats";
import Delta from "./Delta";
import type {Bucket, TimeRange, Unit} from "./range";
import {inRange} from "./range";
import {BandChart, LineChart} from "./Trends";
import "./analytics.css";
import "./communitySpeed.css";

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
  const spread = useMemo(() => spreadOverTime(list, times), [list, times])
  const within = useMemo(() => withinOverTime(list, times), [list, times])
  const trends = useMemo(() => mode ? undefined : modeTrends(list, times), [list, times, mode])
  const split = useMemo(() => singleVsHybrid(times), [times])
  const splitBefore = useMemo(() => earlier && singleVsHybrid(earlier), [earlier])
  const histogram = useMemo(() => speedHistogram(times), [times])
  const perMode = useMemo(() => medianPerMode(times, mode), [times, mode])
  const {fastest, slowest} = useMemo(() => extremes(times), [times])

  if (times.length === 0) return <p className="muted">None of these beatmaps have a known date they were added, so there is nothing to show.</p>
  const most = Math.max(1, ...perMode.map(it => it.days ?? 0))
  const left = scope.length - times.length
  const points = (value: number | undefined) => value === undefined ? undefined : Math.round(value * 100)
  const share = (days: number) => ({now: points(withinShare(times, days)), then: earlier && points(withinShare(earlier, days))})
  const week = share(7), month = share(30)
  const gaps = `Fewer than ${FEWEST} beatmaps in a ${unit} is a gap, as one or two say little.`

  return (
    <>
      <dl className="figures">
        <div><dt>median days to ranked</dt><dd>{median(times.map(it => it.days))}
          <Delta value={median(times.map(it => it.days))} before={earlier && median(earlier.map(it => it.days))} than={before?.label} lowerIsBetter/></dd></div>
        <div><dt>beatmaps with both dates</dt><dd>{times.length}<Delta value={times.length} before={earlier?.length} than={before?.label}/></dd></div>
        <div><dt>ranked within a week of being added</dt><dd>{week.now ?? "–"}%<PointsDelta value={week.now} before={week.then} than={before?.label}/></dd></div>
        <div><dt>ranked within a month</dt><dd>{month.now ?? "–"}%<PointsDelta value={month.now} before={month.then} than={before?.label}/></dd></div>
      </dl>
      {left > 0 && <p className="muted figures-than">{left} without a date they were added are left out</p>}

      <section className="stat">
        <h2>Days to ranked per {unit}</h2>
        <p className="muted">From being added to the planner until ranked, by the {unit} it was ranked: the median, and the bar behind it where the middle half of the beatmaps fall. {gaps}</p>
        <BandChart points={spread.map(it => ({...it, low: it.p25, middle: it.median, high: it.p75}))} unit="days"
                   names={["25th percentile", "Median", "75th percentile"]} label={`Median days from added to ranked per ${unit}, with the middle half`}/>
      </section>

      <section className="stat">
        <h2>Ranked quickly</h2>
        <p className="muted">The share of the beatmaps ranked that {unit} that took less than a week, and less than a month, after being added. {gaps}</p>
        <LineChart slots={within} share counts={within.map(it => it.count)} label={`Share ranked within a week and within a month of being added, per ${unit}`}
                   series={[{name: "Within a week", values: within.map(it => it.week)}, {name: "Within a month", values: within.map(it => it.month)}]}/>
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

      {trends &&
        <section className="stat">
          <h2>Per mode over time</h2>
          <p className="muted">Median days to ranked per {unit} in each mode, each on its own scale. A hybrid counts in each of its modes. {gaps}</p>
          <div className="an-multiples">
            {trends.map(it => (
              <div key={it.mode} className={`an-multiple in-${MODE_WORDS[it.mode]}`}>
                <h3><ModeIcon mode={it.mode}/>{GAMEMODE_NAMES[it.mode]} <span className="muted">{it.count}</span></h3>
                <LineChart slots={list} series={[{name: "Median days", values: it.medians}]} counts={it.counts} unit="days" height={110}
                           label={`Median days to ranked per ${unit}, ${GAMEMODE_NAMES[it.mode]}`}/>
              </div>
            ))}
          </div>
        </section>
      }

      <section className="stat">
        <h2>One mode or more</h2>
        <p className="muted">Beatmaps with a single mode against hybrids with more than one.</p>
        <dl className="figures">
          <div><dt>median days, one mode</dt><dd>{split.single.days ?? "–"}<Delta value={split.single.days} before={splitBefore?.single.days} than={before?.label} lowerIsBetter/></dd></div>
          <div><dt>beatmaps with one mode</dt><dd>{split.single.count}<Delta value={split.single.count} before={splitBefore?.single.count} than={before?.label}/></dd></div>
          <div><dt>median days, hybrid</dt><dd>{split.hybrid.days ?? "–"}<Delta value={split.hybrid.days} before={splitBefore?.hybrid.days} than={before?.label} lowerIsBetter/></dd></div>
          <div><dt>hybrids</dt><dd>{split.hybrid.count}<Delta value={split.hybrid.count} before={splitBefore?.hybrid.count} than={before?.label}/></dd></div>
        </dl>
      </section>

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

/** Like Delta, for percentages: "+5 points vs 2025", so it isn't read as a number of beatmaps */
function PointsDelta({value, before, than}: {value: number | undefined, before: number | undefined, than: string | undefined}) {
  if (value === undefined || before === undefined || !than) return null
  const change = value - before
  return (
    <span className="delta" data-trend={change === 0 ? "same" : change > 0 ? "good" : "bad"}>
      <span aria-hidden>{change > 0 ? "▲ " : change < 0 ? "▼ " : ""}</span>
      {change > 0 ? "+" : change < 0 ? "−" : "±"}{Math.abs(change)} {Math.abs(change) === 1 ? "point" : "points"} vs {than}
    </span>
  )
}
