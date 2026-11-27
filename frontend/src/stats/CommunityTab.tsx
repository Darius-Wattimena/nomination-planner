import {useMemo} from "react";
import type {Beatmap, Gamemode} from "../models/Types";
import {Lorenz, SplitColumns} from "./Charts";
import Columns from "./Columns";
import {activeNominators, firstNominations, lorenz, mappersOverTime, newcomers, nominationCounts, retention, topShare} from "./community";
import type {Period} from "./timeline";
import {timeline} from "./timeline";
import "./analytics.css";

interface CommunityProps {
  /** In the mode and period */
  scope: Beatmap[]
  /** In the mode, of all time */
  all: Beatmap[]
  /** The first ranked beatmap of every mapper, of every mode */
  debuts: Set<string>
  period: Period
  mode?: Gamemode
}

const percent = (value: number | undefined) => value === undefined ? "–" : `${Math.round(value * 100)}%`

/** Who keeps the planner going, and whether new people join */
export default function Community({scope, all, debuts, period, mode}: CommunityProps) {
  const line = useMemo(() => timeline(all, period), [all, period])
  const unfinished = !period.year || period.year === new Date().getFullYear()
  const active = useMemo(() => activeNominators(line, all, mode), [line, all, mode])
  const firsts = useMemo(() => firstNominations(all, mode), [all, mode])
  const fresh = useMemo(() => newcomers(line, firsts), [line, firsts])
  const kept = useMemo(() => retention(all, firsts, mode), [all, firsts, mode])
  const counts = useMemo(() => nominationCounts(scope, mode), [scope, mode])
  const curve = useMemo(() => lorenz(counts), [counts])
  const mappers = useMemo(() => mappersOverTime(line, all, debuts).map(it => ({...it, second: it.returning})), [line, all, debuts])

  return (
    <>
      <div className="stat-pair">
        <section className="stat">
          <h2>Active nominators per {line.unit}</h2>
          <p className="muted">Different nominators on the beatmaps ranked that {line.unit}.</p>
          <Columns columns={active} unit="nominators" label={`Active nominators per ${line.unit}`} unfinished={unfinished} height={180}/>
        </section>
        <section className="stat">
          <h2>Newcomers per {line.unit}</h2>
          <p className="muted">Nominators with their first ranked nomination ever that {line.unit}.</p>
          <Columns columns={fresh} unit="newcomers" label={`Nominators with their first ranked nomination per ${line.unit}`} unfinished={unfinished} height={180}/>
        </section>
      </div>

      <div className="stat-pair">
        <section className="stat">
          <h2>Staying around</h2>
          <p className="muted">Of the nominators who started 6 months ago or earlier, those with something ranked in the last 3 months.</p>
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
        <h2>Mappers per {line.unit}</h2>
        <p className="muted">Different mappers with a beatmap ranked that {line.unit}, split into first-timers and those who had one before.</p>
        <SplitColumns columns={mappers} names={["First time", "Returning"]} label={`Mappers per ${line.unit}`}/>
      </section>
    </>
  )
}
