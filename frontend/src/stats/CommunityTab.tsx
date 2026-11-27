import {useMemo, useState} from "react";
import {Link} from "react-router";
import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, Gamemode} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {displayName} from "../utils/users";
import {Lorenz, SplitColumns} from "./Charts";
import Columns from "./Columns";
import type {Mover} from "./community";
import {activeNominators, activity, AWAY_MONTHS, firstNominations, lorenz, mappersOverTime, modeShare, movement, newcomers, nominationCounts, nominatorCount, nominatorsPerMode, retention, topShare, workloadSpread} from "./community";
import Delta from "./Delta";
import type {Bucket, TimeRange, Unit} from "./range";
import {inRange} from "./range";
import {rankedDate} from "./stats";
import {ShareColumns} from "./Trends";
import "./analytics.css";
import "./communitySpeed.css";

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
  const earlier = useMemo(() => before ? all.filter(it => inRange(rankedDate(it), before)) : undefined, [all, before])
  const then = before && earlier && headline(earlier, before)
  const people = useMemo(() => activity(all, mode), [all, mode])
  const moved = useMemo(() => movement(people, range), [people, range])
  const movedBefore = useMemo(() => before ? movement(people, before) : undefined, [people, before])
  const shares = useMemo(() => mode ? undefined : modeShare(list, scope), [list, scope, mode])
  const perMode = useMemo(() => mode ? undefined : nominatorsPerMode(scope), [scope, mode])
  const perModeBefore = useMemo(() => earlier && !mode ? nominatorsPerMode(earlier) : undefined, [earlier, mode])
  const workload = useMemo(() => workloadSpread(counts), [counts])

  return (
    <>
      <dl className="figures">
        <div><dt>active nominators</dt><dd>{now.nominators}<Delta value={now.nominators} before={then?.nominators} than={before?.label}/></dd></div>
        <div><dt>{now.newcomers === 1 ? "newcomer" : "newcomers"}</dt><dd>{now.newcomers}<Delta value={now.newcomers} before={then?.newcomers} than={before?.label}/></dd></div>
        <div><dt>mappers</dt><dd>{now.mappers}<Delta value={now.mappers} before={then?.mappers} than={before?.label}/></dd></div>
      </dl>

      <section className="stat">
        <h2>Joined, stopped and came back</h2>
        <p className="muted">Nominators by when they had something ranked. {range.label}{mode ? `, ${GAMEMODE_NAMES[mode]}` : ""}.</p>
        <div className="cs-people">
          <People title="Joined" hint="Their first ranked nomination ever." list={moved.joined} before={movedBefore?.joined.length} than={before?.label}/>
          {moved.stopped
            ? <People title="Stopped" hint={`Something ranked in the ${AWAY_MONTHS} months before this period, nothing in it.`} list={moved.stopped} before={movedBefore?.stopped?.length} than={before?.label} lowerIsBetter last/>
            : <div><h3>Stopped</h3><p className="muted">Only shown for periods of 4 weeks or longer, as a shorter quiet time says little.</p></div>
          }
          <People title="Came back" hint={`Something ranked after ${AWAY_MONTHS} months or more without.`} list={moved.cameBack} before={movedBefore?.cameBack.length} than={before?.label}/>
        </div>
      </section>

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

      {shares &&
        <section className="stat">
          <h2>Mode share per {unit}</h2>
          <p className="muted">Each mode's share of the ranked nominations that {unit}: every nominator on a mode of a ranked beatmap counts once. Empty means nothing was ranked.</p>
          <ShareColumns columns={shares} label={`Share of the ranked nominations per mode, per ${unit}`}/>
        </section>
      }

      <div className="stat-pair">
        {perMode &&
          <section className="stat">
            <h2>Nominators per mode</h2>
            <p className="muted">Different nominators with a ranked nomination in each mode. Someone in two modes counts in both.</p>
            <dl className="figures cs-modes">
              {perMode.map(it => (
                <div key={it.mode}>
                  <dt><ModeIcon mode={it.mode}/>{GAMEMODE_NAMES[it.mode]}</dt>
                  <dd>{it.count}<Delta value={it.count} before={perModeBefore?.find(own => own.mode === it.mode)?.count} than={before?.label}/></dd>
                </div>
              ))}
            </dl>
          </section>
        }
        <section className="stat">
          <h2>Workload</h2>
          <p className="muted">Active nominators by how many ranked nominations they had in this period.</p>
          <Columns columns={workload} unit="nominators" label={`Active nominators by their number of ranked nominations, ${counts.length} in all`} height={180}/>
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

/** Names shown before "Show all" */
const SHOWN = 10

function People({title, hint, list, before, than, lowerIsBetter, last}: {
  title: string
  hint: string
  list: Mover[]
  before: number | undefined
  than: string | undefined
  lowerIsBetter?: boolean
  /** The date is their last ranked nomination */
  last?: boolean
}) {
  const [all, setAll] = useState(false)
  const shown = all ? list : list.slice(0, SHOWN)
  return (
    <div>
      <h3>{title} <span className="cs-count">{list.length}</span></h3>
      <Delta value={list.length} before={before} than={than} lowerIsBetter={lowerIsBetter}/>
      <p className="muted">{hint}</p>
      {list.length === 0
        ? <p className="muted">Nobody.</p>
        : <ul className="cs-names">
          {shown.map(({user, date}) => (
            <li key={user.osuId}>
              <Link to={`/team?user=${user.osuId}`}>{displayName(user)}</Link>
              <span className="muted">{last && "last "}{date.toLocaleDateString("en", {day: "numeric", month: "short", year: "numeric"})}</span>
            </li>
          ))}
        </ul>
      }
      {list.length > SHOWN &&
        <button className="link" aria-expanded={all} onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${list.length}`}</button>
      }
    </div>
  )
}
