import {useState} from "react";
import {FaArrowUpRightFromSquare, FaCheck, FaPlus, FaRegComments, FaRegNoteSticky} from "react-icons/fa6";
import {GAMEMODE_NAMES, MISSING_USER_ID, ROLES} from "../Constants";
import {Beatmap, BeatmapGamemode} from "../models/Types";
import Api from "../resources/Api";
import {request} from "../resources/client";
import Cover from "../ui/Cover";
import ModeIcon from "../ui/ModeIcon";
import {Avatar} from "../ui/Person";
import StatusBadge from "../ui/StatusBadge";
import {useToast} from "../ui/Toast";
import {canEdit, useSession} from "../utils/session";
import {canClaim, highestRole, isMissing, sortedModes} from "../utils/users";

interface BeatmapRowProps {
  beatmap: Beatmap
  onOpen: () => void
  onChanged: () => void
}

export default function BeatmapRow({beatmap, onOpen, onChanged}: BeatmapRowProps) {
  const osuUrl = `https://osu.ppy.sh/beatmapsets/${beatmap.osuId}`

  return (
    <li className="beatmap">
      <button className="beatmap-cover" onClick={onOpen} tabIndex={-1} aria-hidden>
        <Cover id={beatmap.osuId} size="card"/>
      </button>
      <div className="beatmap-info">
        <button className="beatmap-title" onClick={onOpen}>{beatmap.title}</button>
        <span className="beatmap-artist">{beatmap.artist}</span>
        <span className="muted">mapped by {beatmap.mapper.username}</span>
      </div>
      <div className="beatmap-status"><StatusBadge status={beatmap.status}/></div>
      <div className="beatmap-modes">
        {sortedModes(beatmap).map(mode =>
          <ModeSlots key={mode.gamemode} beatmap={beatmap} mode={mode} onChanged={onChanged}/>
        )}
      </div>
      <div className="beatmap-note">
        {beatmap.note?.trim() && <p title={beatmap.note}><FaRegNoteSticky aria-label="Note"/><span>{beatmap.note}</span></p>}
      </div>
      <div className="beatmap-links">
        <a className="icon-button" href={osuUrl} target="_blank" rel="noreferrer" aria-label="Open on osu!" title="Open on osu!">
          <FaArrowUpRightFromSquare/>
        </a>
        <a className="icon-button" href={`${osuUrl}/discussion`} target="_blank" rel="noreferrer" aria-label="Open discussion" title="Open discussion">
          <FaRegComments/>
        </a>
      </div>
    </li>
  )
}

function ModeSlots({beatmap, mode, onChanged}: {beatmap: Beatmap, mode: BeatmapGamemode, onChanged: () => void}) {
  const session = useSession()
  const toast = useToast()
  const [claiming, setClaiming] = useState(false)

  const nominated = mode.nominators.filter(it => it.hasNominated).length
  const claimable = canEdit(session) && canClaim(beatmap, mode, session?.user)
  // Only offer a single claim button, even when both slots are open
  const claimIndex = claimable ? mode.nominators.findIndex(it => isMissing(it.nominator)) : -1

  async function claim() {
    setClaiming(true)
    try {
      await request(Api.updateNominator(beatmap.osuId, mode.gamemode, MISSING_USER_ID, session!.user!.osuId))
      toast(`You are now nominator of ${beatmap.title}`)
      onChanged()
    } catch {
      toast("Could not claim this beatmap", "error")
    } finally {
      setClaiming(false)
    }
  }

  return (
    <div className="mode-slots">
      <ModeIcon mode={mode.gamemode}/>
      <span className={`progress progress-${nominated}`} aria-label={`${nominated} of ${mode.nominators.length} nominated`} title="Nominations">
        {nominated}/{mode.nominators.length}
      </span>
      {mode.nominators.map((slot, index) => {
        if (index === claimIndex) {
          return (
            <button key={index} className="slot slot-claim" disabled={claiming} onClick={claim}
                    aria-label={`Claim ${beatmap.title} for ${GAMEMODE_NAMES[mode.gamemode]}`}>
              <FaPlus/>Claim
            </button>
          )
        }
        if (isMissing(slot.nominator)) {
          return <span key={index} className="slot slot-open">Open</span>
        }
        const role = ROLES[highestRole(slot.nominator, mode.gamemode)]
        return (
          <span key={index} className={`slot ${role.className} ${slot.hasNominated ? "slot-done" : ""}`} title={`${slot.nominator.username} · ${role.name}`}>
            <Avatar user={slot.nominator}/>
            <span className="slot-name">{slot.nominator.username}</span>
            {slot.hasNominated && <FaCheck aria-label="Nominated"/>}
          </span>
        )
      })}
    </div>
  )
}
