import {Link} from "react-router";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Gamemode} from "../models/Types";
import {usePlanner} from "../resources/planner";
import ModeIcon from "../ui/ModeIcon";
import {MODE_WORDS} from "../utils/query";
import {plannedLoad, stages as stagesOf, supply} from "./pipeline";


/** Where the process needs help, about the work and not about people */
export default function Health() {
  const {maps = []} = usePlanner()
  const stages = stagesOf(maps)
  const most = Math.max(1, ...stages.map(it => it.count))
  const {slots, people, nominators} = plannedLoad(maps)

  return (
    <div className="health">
      <section className="card">
        <h2>Open spots per mode</h2>
        <p className="muted">How many spots are open, and how many nominators the mode has.</p>
        <div className="table-scroll">
          <table className="health-table">
            <thead><tr><th scope="col">Mode</th><th scope="col">Open spots</th><th scope="col">Nominators</th><th scope="col">Nothing planned</th><th scope="col">Open per nominator</th></tr></thead>
            <tbody>{GAMEMODES.map(mode => <ModeHealth key={mode} mode={mode}/>)}</tbody>
          </table>
        </div>
      </section>
      <section className="card">
        <h2>Where beatmaps wait</h2>
        <p className="muted">With the median time since their last update. A sync sets this to the date on osu!.</p>
        <ul className="bars bars-wide">
          {stages.map(it => (
            <li key={it.id}>
              <Link className="bars-name" to={`/beatmaps?q=stage%3A${it.id}`}>{it.label}</Link>
              <span className="bars-track"><span style={{width: `${it.count / most * 100}%`}}/></span>
              <span className="bars-value">{it.count}{it.days !== undefined && <span className="muted"> {it.days}d</span>}</span>
            </li>
          ))}
        </ul>
        {slots > 0 &&
          <p>{people} of {nominators} {people === 1 ? "nominator is" : "nominators are"} on half of the {slots} nominations that are planned.</p>
        }
      </section>
    </div>
  )
}

function ModeHealth({mode}: {mode: Gamemode}) {
  const {maps = [], nominators} = usePlanner()
  const {open, team, free} = supply(maps, nominators, mode)
  const unknown = "…"

  return (
    <tr>
      <th scope="row"><Link to={`/beatmaps?q=mode%3A${MODE_WORDS[mode]}%20is%3Aopen`}><ModeIcon mode={mode}/>{GAMEMODE_NAMES[mode]}</Link></th>
      <td>{open}</td>
      <td>{team ?? unknown}</td>
      <td>{free !== undefined ? free : unknown}</td>
      <td>{team ? (open / team).toFixed(1) : team === 0 ? "–" : unknown}</td>
    </tr>
  )
}
