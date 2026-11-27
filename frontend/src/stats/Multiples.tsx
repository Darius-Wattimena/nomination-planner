import {useMemo} from "react";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {MODE_WORDS} from "../utils/query";
import Columns from "./Columns";
import {rankedDate} from "./stats";
import type {Period} from "./timeline";
import {group, timeline} from "./timeline";
import "./analytics.css";

/** The same columns once for every mode, each on its own scale, to compare their shape */
export default function Multiples({maps, period, unfinished}: {maps: Beatmap[], period: Period, unfinished: boolean}) {
  const perMode = useMemo(() => {
    const line = timeline(maps, period)
    return {unit: line.unit, modes: GAMEMODES.map(mode => {
      const groups = group(line, maps.filter(map => map.gamemodes.some(it => it.gamemode === mode)), rankedDate)
      return {mode, columns: line.columns.map((column, index) => ({...column, value: groups[index].length}))}
    })}
  }, [maps, period])

  return (
    <section className="stat">
      <h2>Per mode</h2>
      <p className="muted">Ranked per {perMode.unit} in each mode, each on its own scale. A hybrid counts in each of its modes.</p>
      <div className="an-multiples">
        {perMode.modes.map(({mode, columns}) => (
          <div key={mode} className={`an-multiple in-${MODE_WORDS[mode]}`}>
            <h3><ModeIcon mode={mode}/>{GAMEMODE_NAMES[mode]} <span className="muted">{columns.reduce((sum, it) => sum + it.value, 0)}</span></h3>
            <Columns columns={columns} unit="ranked" label={`Ranked ${GAMEMODE_NAMES[mode]} beatmaps per ${perMode.unit}`} unfinished={unfinished} height={110}/>
          </div>
        ))}
      </div>
    </section>
  )
}
