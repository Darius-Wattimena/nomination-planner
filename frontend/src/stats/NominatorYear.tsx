import {useMemo, useState} from "react";
import {Link} from "react-router-dom";
import {Beatmap, Pairing, User} from "../models/Types";
import {usePlanner} from "../resources/planner";
import {useRanked} from "../resources/ranked";
import {Avatar, RoleBadges} from "../ui/Person";
import {Loading} from "../ui/States";
import {canNominate} from "../utils/users";
import {badges, scores} from "./boards";
import Columns from "./Columns";
import {Badges} from "./Leaderboards";
import {debuts, nominatorsOf, perMonth, rankedDate, topBy} from "./stats";

interface NominatorYearProps {
  id: string
  self: boolean
}

/** What somebody helped to get ranked, seen as a year to look back on */
export default function NominatorYear({id, self}: NominatorYearProps) {
  const {maps, error, retry} = useRanked()
  const own = useMemo(() => (maps ?? [])
    .filter(map => nominatorsOf(map).some(it => it.osuId === id) && rankedDate(map))
    .sort((a, b) => rankedDate(b)!.getTime() - rankedDate(a)!.getTime()), [maps, id])
  const years = Array.from(new Set(own.map(it => rankedDate(it)!.getFullYear())))
  const [picked, setPicked] = useState<number>()
  const year = picked && years.includes(picked) ? picked : years[0]
  const firsts = useMemo(() => new Set(debuts(maps ?? []).map(it => it.osuId)), [maps])

  if (error) return <p className="alert alert-error" role="alert">Could not load the ranked beatmaps. <button className="link" onClick={retry}>Try again</button></p>
  if (!maps) return <Loading/>
  if (own.length === 0 || !year) return null

  const now = new Date()
  const inYear = own.filter(it => rankedDate(it)!.getFullYear() === year)
  const last = year === now.getFullYear() ? now.getMonth() : 11
  const months = perMonth(inYear, year * 12, year * 12 + last).map(it => ({...it, short: it.label.slice(0, 3)}))
  const busiest = months.reduce((best, it) => it.value > best.value ? it : best, months[0])
  const partners = topBy(inYear.flatMap(map => map.gamemodes
    .filter(mode => mode.nominators.some(it => it.nominator.osuId === id))
    .flatMap(mode => mode.nominators).map(it => it.nominator)
    .filter(it => it.osuId !== id && it.osuId !== "0")
    // Once per beatmap
    .filter((user, index, users) => users.findIndex(it => it.osuId === user.osuId) === index)), it => it.osuId)
  const helped = inYear.filter(it => firsts.has(it.osuId))

  return (
    <section className="desk-group year">
      <header>
        <h2>{self ? "Your" : "Their"} {year}</h2>
        {years.length > 1 &&
          <select aria-label="Year" id="year" value={year} onChange={event => setPicked(Number(event.target.value))}>
            {years.map(it => <option key={it}>{it}</option>)}
          </select>
        }
      </header>
      <dl className="figures">
        <div><dt>ranked</dt><dd>{inYear.length}</dd></div>
        <div><dt>most active month</dt><dd>{busiest.label.split(" ")[0]}</dd></div>
        {partners[0] && <div><dt>most with</dt><dd>{partners[0].item.username}</dd></div>}
        <div><dt>{helped.length === 1 ? "first ranked map helped" : "first ranked maps helped"}</dt><dd>{helped.length}</dd></div>
      </dl>
      <Columns columns={months} unit="ranked" label={`Ranked beatmaps per month in ${year}`} unfinished={year === now.getFullYear()} height={150}/>
      {helped.length > 0 && <p className="muted">First ranked beatmaps of {helped.map((it: Beatmap) => it.mapper.username).slice(0, 6).join(", ")}{helped.length > 6 && ` and ${helped.length - 6} more`}.</p>}
    </section>
  )
}

export function NominatorBadges({id}: {id: string}) {
  const {maps} = useRanked()
  const earned = useMemo(() => {
    const all = maps ?? []
    const own = all.filter(map => nominatorsOf(map).some(it => it.osuId === id))
    return badges(scores(own, new Set(debuts(all).map(it => it.osuId))).find(it => it.user.osuId === id))
  }, [maps, id])
  if (!maps) return null

  return (
    <section className="desk-group">
      <header><h2>Badges</h2></header>
      <Badges badges={earned}/>
    </section>
  )
}

/** People for the same modes that they never ranked anything with */
export function NewPairings({user, pairings}: {user: User, pairings: Pairing[]}) {
  const modes = user.gamemodes.filter(it => it.role !== "Mapper").map(it => it.gamemode)
  const {nominators = []} = usePlanner()
  const known = new Set(pairings.filter(it => it.pairingRanked > 0 || it.pairingPending > 0).map(it => it.name))
  const fresh = nominators.filter(it => it.osuId !== user.osuId && !known.has(it.username) && modes.some(mode => canNominate(it, mode))).slice(0, 6)
  if (fresh.length === 0) return null

  return (
    <section className="desk-group">
      <header><h2>Not nominated together yet</h2><span className="muted">They nominate your modes too</span></header>
      <ul className="fresh">
        {fresh.map(it => <li key={it.osuId}><Link to={`/team?user=${it.osuId}`}><Avatar user={it}/><span><strong>{it.username}</strong><RoleBadges user={it}/></span></Link></li>)}
      </ul>
    </section>
  )
}
