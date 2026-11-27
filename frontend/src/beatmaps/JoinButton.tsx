import {GAMEMODE_NAMES} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {useNominators} from "./actions";

interface JoinButtonProps {
  beatmap: Beatmap
  mode: Gamemode
  user: User
  className?: string
  /** Gets how to undo it */
  onJoined?: (undo: () => void) => void
}

/** Join a beatmap as nominator of a mode */
export default function JoinButton({beatmap, mode, user, className = "button button-tint", onJoined}: JoinButtonProps) {
  const {join} = useNominators(beatmap)
  return (
    <button className={className} aria-label={`I'll nominate ${beatmap.title} for ${GAMEMODE_NAMES[mode]}`} onClick={() => onJoined?.(join(mode, user))}>
      I'll nominate
    </button>
  )
}
