import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {useNominators} from "./actions";

/** Join a beatmap as nominator of a mode */
export default function JoinButton({beatmap, mode, user, className = "button button-tint"}: {beatmap: Beatmap, mode: Gamemode, user: User, className?: string}) {
  const {join} = useNominators(beatmap)
  return (
    <button className={className} aria-label={`I'll nominate ${beatmap.title} for ${GAMEMODE_NAMES[mode]}`} onClick={() => join(mode, user)}>
      I'll nominate
    </button>
  )
}
