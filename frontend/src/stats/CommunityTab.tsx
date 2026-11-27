import {useMemo} from "react";
import type {Beatmap, Gamemode} from "../models/Types";
import {Lorenz, SplitColumns} from "./Charts";
import Columns from "./Columns";
import {activeNominators, firstNominations, lorenz, mappersOverTime, newcomers, nominationCounts, nominatorCount, retention, topShare} from "./community";
import Delta from "./Delta";
import type {Bucket, TimeRange, Unit} from "./range";
import {inRange} from "./range";
import {rankedDate} from "./stats";
import "./analytics.css";

interface CommunityProps {
  /** In the mode and range */
  scope: Beatmap[]
  /** In the mode, of all time */
  all: Beatmap[]
  /** The first ranked beatmap of every mapper, of every mode */
  debuts: Set<string>
  range: TimeRange
  /** The buckets of the range */
  list: Bucket[]
  unit: Unit
  /** The range to compare to */
  before?: TimeRange
  mode?: Gamemode
}

const percent = (value: number | undefined) => value === undefined ? "–" : `${Math.round(value * 100)}%`

/** Who keeps the planner going, and whether new people join */
export default function Community({scope, all, debuts, range, list, unit, before, mode}: CommunityProps) {
  const active = useMemo(() => activeNominators(list, scope, mode), [list, scope, mode])
  const firsts = useMemo(() => firstNominations(all, mode), [all, mode])
  const fresh = useMemo(() => newcomers(list, firsts), [list, firsts])
  // At the end of the range, so a past year shows how it was then
  const kept = useMemo(() => retention(all, firsts, mode, range.to), [all, firsts, mode, range])
  const counts = useMemo(() => nominationCounts(scope, mode), [scope, mode])
  const curve = useMemo(() => lorenz(counts), [counts])
  const mappers = useMemo(() => mappersOverTime(list, scope, debuts).map(it => ({...it, second: it.returning})), [list, scope, debuts])
  const headline = (maps: Beatmap[], within: TimeRange) => ({
    nominators: nominatorCount(maps, mode),
    newcomers: Array.from(firsts.values()).filter(it => inRange(it, within)).length,
    mappers: new Set(maps.map(it => it.mapper.osuId)).size
  })
  const now = headline(scope, range)
  const then = before && headline(all.filter(it => inRange(rankedDate(it), before)), before)

  return (
    <>
      <dl className="figures">
        <div><dt>active nominators</dt><dd>{now.nominators}<Delta value={now.nominators} before={then?.nominators} than={before?.label}/></dd></div>
        <div><dt>{now.newcomers === 1 ? "newcomer" : "newcomers"}</dt><dd>{now.newcomers}<Delta value={now.newcomers} before={then?.newcomers} than={before?.label}/></dd></div>
        <div><dt>mappers</dt><dd>{now.mappers}<Delta value={now.mappers} before={then?.mappers} than={before?.label}/></dd></div>
      </dl>

      <div className="stat-pair">
        <section className="stat">
          <h2>Active nominators per {unit}</h2>
          <p className="muted">Different nominators on the beatmaps ranked that {unit}.</p>
          <Columns columns={active} unit="nominators" label={`Active nominators per ${unit}`} height={180}/>
        </section>
        <section className="stat">
          <h2>Newcomers per {unit}</h2>
          <p className="muted">Nominators with their first ranked nomination ever that {unit}.</p>
          <Columns columns={fresh} unit="newcomers" label={`Nominators with their first ranked nomination per ${unit}`} height={180}/>
        </section>
      </div>

      <div className="stat-pair">
        <section className="stat">
          <h2>Staying around</h2>
          <p className="muted">Of the nominators who started 6 months or more before the end of the period, those with something ranked in its last 3 months.</p>
          <dl className="figures">
            <div><dt>still ranking</dt><dd>{percent(kept.share)}</dd></div>
            <div><dt>of {kept.eligible} nominators</dt><dd>{kept.retained}</dd></div>
          </dl>
        </section>
        <section className="stat">
          <h2>Who does the work</h2>
          <p className="muted">The share of the ranked nominations in this period done by the busiest nominators.</p>
          <div className="an-concentration">
            <dl className="figures">
              <div><dt>by the top 10%</dt><dd>{percent(topShare(counts, .1))}</dd></div>
              <div><dt>by the top 25%</dt><dd>{percent(topShare(counts, .25))}</dd></div>
            </dl>
            {curve.length > 1 && <Lorenz points={curve} label={`Of ${counts.length} nominators, the top 25% did ${percent(topShare(counts, .25))} of the nominations`}/>}
          </div>
        </section>
      </div>

      <section className="stat">
        <h2>Mappers per {unit}</h2>
        <p className="muted">Different mappers with a beatmap ranked that {unit}, split into first-timers and those who had one before.</p>
        <SplitColumns columns={mappers} names={["First time", "Returning"]} label={`Mappers per ${unit}`}/>
      </section>
    </>
  )
}
