import {GAMEMODE_NAMES, MISSING_USER_ID} from "../Constants";
import type {Beatmap, Gamemode, User} from "../models/Types";
import {changeNominator, ConflictError, NOBODY} from "../resources/planner";
import {useToast} from "../ui/useToast";

/** Change the nominators of a beatmap. Everything can be undone, so nothing has to be confirmed up front. */
export function useNominators(beatmap: Beatmap) {
  const toast = useToast()

  function change(mode: Gamemode, oldId: string, user: User, message: string) {
    const undo = changeNominator(beatmap.osuId, mode, oldId, user, error => toast(error instanceof ConflictError
      ? `Someone else changed ${beatmap.title}. It now shows the latest nominators.`
      : `Could not change the nominators of ${beatmap.title}.`, {kind: "error"}))
    toast(message, {undo})
  }

  return {
    join: (mode: Gamemode, user: User) =>
      change(mode, MISSING_USER_ID, user, `You plan to nominate ${beatmap.title} (${GAMEMODE_NAMES[mode]})`),
    assign: (mode: Gamemode, oldId: string, user: User) =>
      change(mode, oldId, user, `${user.username} plans to nominate ${beatmap.title}`),
    remove: (mode: Gamemode, user: User) =>
      change(mode, user.osuId, NOBODY, `${user.username} no longer nominates ${beatmap.title}`)
  }
}
