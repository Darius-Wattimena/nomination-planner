import {useMemo} from "react";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import type {Beatmap} from "../models/Types";
import ModeIcon from "../ui/ModeIcon";
import {MODE_WORDS} from "../utils/query";
import Columns from "./Columns";
import type {Bucket, Unit} from "./range";
import {count} from "./range";
import {rankedDate} from "./stats";
import "./analytics.css";

/** The same columns once for every mode, each on its own scale, to compare their shape */
export default function Multiples({maps, list, unit}: {maps: Beatmap[], list: Bucket[], unit: Unit}) {
  const modes = useMemo(() => GAMEMODES.map(mode => ({mode, columns: count(list, maps.filter(map => map.gamemodes.some(it => it.gamemode === mode)), rankedDate)})), [maps, list])

  return (
    <section className="stat">
      <h2>Per mode</h2>
      <p className="muted">Ranked per {unit} in each mode, each on its own scale. A hybrid counts in each of its modes.</p>
      <div className="an-multiples">
        {modes.map(({mode, columns}) => (
          <div key={mode} className={`an-multiple in-${MODE_WORDS[mode]}`}>
            <h3><ModeIcon mode={mode}/>{GAMEMODE_NAMES[mode]} <span className="muted">{columns.reduce((sum, it) => sum + it.value, 0)}</span></h3>
            <Columns columns={columns} unit="ranked" label={`Ranked ${GAMEMODE_NAMES[mode]} beatmaps per ${unit}`} height={110}/>
          </div>
        ))}
      </div>
    </section>
  )
}
