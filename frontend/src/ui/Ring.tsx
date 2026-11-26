import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, BeatmapGamemode} from "../models/Types";
import {roleOf, spots} from "../utils/nominations";
import {isMissing, sortedModes} from "../utils/users";
import ModeIcon from "./ModeIcon";

/** The approach circle of a mode, which closes once everyone nominated. Two halves for the main mode, as that one needs two. */
export function Ring({beatmap, mode, large}: {beatmap: Beatmap, mode: BeatmapGamemode, large?: boolean}) {
  const shown = spots(beatmap, mode).filter(it => !it.optional)
  const states = shown.map(it => it.hasNominated ? "done" : isMissing(it.nominator) ? "open" : "assigned")
  const label = shown.map(it =>
    isMissing(it.nominator) ? "open spot" : `${it.nominator.username}${it.hasNominated ? " nominated" : ""}`).join(", ")
  const text = `${GAMEMODE_NAMES[mode.gamemode]}${beatmap.gamemodes.length > 1 && roleOf(beatmap, mode) === "main" ? ", main mode" : ""}: ${label}`

  return (
    <span className={large ? "ring ring-large" : "ring"} role="img" aria-label={text} title={text}>
      <svg viewBox="0 0 40 40" aria-hidden>
        {states.length === 1
          ? <circle className={`arc arc-${states[0]}`} cx="20" cy="20" r="18"/>
          : <>
              <path className={`arc arc-${states[0]}`} d="M17.4 37.8 A18 18 0 0 1 17.4 2.2"/>
              <path className={`arc arc-${states[1]}`} d="M22.6 2.2 A18 18 0 0 1 22.6 37.8"/>
            </>
        }
      </svg>
      <ModeIcon mode={mode.gamemode}/>
    </span>
  )
}

export function Rings({beatmap}: {beatmap: Beatmap}) {
  return <span className="rings">{sortedModes(beatmap).map(mode => <Ring key={mode.gamemode} beatmap={beatmap} mode={mode}/>)}</span>
}
