import {useEffect, useState} from "react";
import {FaArrowUpRightFromSquare, FaCheck, FaPlus, FaRegComments, FaTrashCan, FaUserPen, FaUserPlus, FaXmark} from "react-icons/fa6";
import {GAMEMODE_NAMES, GAMEMODES, MISSING_USER_ID} from "../Constants";
import {Beatmap, BeatmapGamemode, BeatmapStatus, Gamemode} from "../models/Types";
import Api from "../resources/Api";
import {request, useQuery} from "../resources/client";
import Cover from "../ui/Cover";
import Dialog, {ConfirmDialog} from "../ui/Dialog";
import ModeIcon from "../ui/ModeIcon";
import {Avatar, RoleBadges} from "../ui/Person";
import {EmptyState, Loading} from "../ui/States";
import StatusBadge from "../ui/StatusBadge";
import {useToast} from "../ui/Toast";
import {canEdit, isNat, useSession} from "../utils/session";
import {canClaim, isMissing, sortedModes} from "../utils/users";
import UserPicker from "./UserPicker";

interface BeatmapDetailsProps {
  id: string | undefined
  onClose: () => void
  onChanged: () => void
}

export default function BeatmapDetails({id, onClose, onChanged}: BeatmapDetailsProps) {
  // Keep showing the beatmap while the panel closes
  const [shownId, setShownId] = useState(id)
  useEffect(() => {
    if (id) setShownId(id)
  }, [id])

  return (
    <Dialog open={!!id} onClose={onClose} title="Beatmap details" side hideHeader>
      <button className="icon-button panel-close" aria-label="Close" onClick={onClose}><FaXmark/></button>
      {shownId && <Details key={shownId} id={shownId} onClose={onClose} onChanged={onChanged}/>}
    </Dialog>
  )
}

type Confirming = "sync" | "status" | "delete"

interface Picking {
  mode: Gamemode
  replacing: string
  selected: string[]
}

function Details({id, onClose, onChanged}: {id: string, onClose: () => void, onChanged: () => void}) {
  const session = useSession()
  const toast = useToast()
  const {data, loading, error, reload} = useQuery<Beatmap>(Api.fetchBeatmap(id))
  const [picking, setPicking] = useState<Picking>()
  const [confirming, setConfirming] = useState<Confirming>()
  const [busy, setBusy] = useState(false)

  if (!data) {
    if (loading) return <Loading/>
    return (
      <EmptyState title={error ? "Could not load this beatmap" : "Beatmap not found"}>
        {error
          ? <button className="button" onClick={reload}>Try again</button>
          : <p className="muted">It may have been removed from the planner.</p>
        }
      </EmptyState>
    )
  }

  const beatmap = data
  const editable = canEdit(session)
  const graved = beatmap.status === BeatmapStatus.Graved
  const newStatus = graved ? BeatmapStatus.Pending : BeatmapStatus.Graved
  const osuUrl = `https://osu.ppy.sh/beatmapsets/${beatmap.osuId}`
  const absentModes = GAMEMODES.filter(mode => !beatmap.gamemodes.some(it => it.gamemode === mode))

  async function setNominator(mode: Gamemode, oldId: string, newId: string, message: string) {
    setBusy(true)
    try {
      await request(Api.updateNominator(beatmap.osuId, mode, oldId, newId))
      toast(message)
      reload()
      onChanged()
    } catch {
      toast("Could not update the nominators", "error")
    } finally {
      setBusy(false)
    }
  }

  async function confirm(kind: Confirming) {
    const actions = {
      sync: {config: Api.syncBeatmap(beatmap.osuId), done: "Beatmap synced", failed: "Could not sync the beatmap"},
      status: {
        config: Api.updateBeatmapStatus(beatmap.osuId, newStatus),
        done: graved ? "Beatmap moved back to pending" : "Beatmap moved to the graveyard",
        failed: "Could not change the status"
      },
      delete: {config: Api.deleteBeatmap(beatmap.osuId), done: "Beatmap deleted", failed: "Could not delete the beatmap"}
    }
    const {config, done, failed} = actions[kind]

    try {
      await request(config)
      toast(done)
      setConfirming(undefined)
      onChanged()
      if (kind === "sync") {
        reload()
      } else {
        onClose()
      }
    } catch {
      toast(failed, "error")
    }
  }

  return (
    <div className="panel">
      <div className="panel-cover">
        <Cover id={beatmap.osuId} size="cover"/>
      </div>
      <header className="panel-header">
        <StatusBadge status={beatmap.status}/>
        <h2>{beatmap.title}</h2>
        <p>{beatmap.artist}</p>
        <div className="panel-mapper"><Avatar user={beatmap.mapper}/>mapped by {beatmap.mapper.username}</div>
        <div className="button-row">
          <a className="button" href={osuUrl} target="_blank" rel="noreferrer"><FaArrowUpRightFromSquare/>osu! page</a>
          <a className="button" href={`${osuUrl}/discussion`} target="_blank" rel="noreferrer"><FaRegComments/>Discussion</a>
        </div>
      </header>

      <section className="panel-section">
        <h3>Nominators</h3>
        {sortedModes(beatmap).map(mode => (
          <ModeCard
            key={mode.gamemode}
            mode={mode}
            editable={editable}
            claimable={editable && canClaim(beatmap, mode, session?.user)}
            busy={busy}
            onClaim={() => setNominator(mode.gamemode, MISSING_USER_ID, session!.user!.osuId, "Beatmap claimed")}
            onRemove={userId => setNominator(mode.gamemode, userId, MISSING_USER_ID, "Nominator removed")}
            onAssign={replacing => setPicking({
              mode: mode.gamemode,
              replacing,
              selected: mode.nominators.map(it => it.nominator.osuId)
            })}
          />
        ))}
        {editable && absentModes.length > 0 &&
          <div className="chips" role="group" aria-label="Add a game mode">
            {absentModes.map(mode => (
              <button key={mode} className="chip" disabled={busy} onClick={() => setPicking({mode, replacing: MISSING_USER_ID, selected: []})}>
                <FaPlus/>{GAMEMODE_NAMES[mode]}
              </button>
            ))}
          </div>
        }
      </section>

      <Note beatmap={beatmap} editable={editable} onSaved={() => {
        reload()
        onChanged()
      }}/>

      {editable &&
        <section className="panel-section">
          <h3>Manage</h3>
          <div className="button-row">
            <button className="button" onClick={() => setConfirming("sync")}>Sync with osu!</button>
            {isNat(session) &&
              <button className="button" onClick={() => setConfirming("status")}>
                {graved ? "Move to pending" : "Move to graveyard"}
              </button>
            }
            <button className="button button-danger-ghost" onClick={() => setConfirming("delete")}><FaTrashCan/>Delete</button>
          </div>
        </section>
      }

      <UserPicker
        open={!!picking}
        onClose={() => setPicking(undefined)}
        title={picking ? `Assign ${GAMEMODE_NAMES[picking.mode]} nominator` : "Assign nominator"}
        selected={picking?.selected ?? []}
        mode={picking?.mode}
        onPick={user => picking && setNominator(picking.mode, picking.replacing, user.osuId, `${user.username} assigned`)}
      />
      <ConfirmDialog
        open={confirming === "sync"} onClose={() => setConfirming(undefined)} onConfirm={() => confirm("sync")}
        title="Sync beatmap" action="Sync beatmap"
      >
        <p>Get the latest details of <b>{beatmap.artist} - {beatmap.title}</b> from osu!?</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming === "status"} onClose={() => setConfirming(undefined)} onConfirm={() => confirm("status")}
        title="Change status" action="Change status"
      >
        <p>Change <b>{beatmap.artist} - {beatmap.title}</b> from {beatmap.status} to <b>{newStatus}</b>?</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming === "delete"} onClose={() => setConfirming(undefined)} onConfirm={() => confirm("delete")}
        title="Delete beatmap" action="Delete beatmap" danger
      >
        <p>Delete <b>{beatmap.artist} - {beatmap.title}</b> by {beatmap.mapper.username} from the planner? This can't be undone.</p>
      </ConfirmDialog>
    </div>
  )
}

interface ModeCardProps {
  mode: BeatmapGamemode
  editable: boolean
  claimable: boolean
  busy: boolean
  onClaim: () => void
  onRemove: (userId: string) => void
  onAssign: (replacing: string) => void
}

function ModeCard({mode, editable, claimable, busy, onClaim, onRemove, onAssign}: ModeCardProps) {
  const nominated = mode.nominators.filter(it => it.hasNominated).length

  return (
    <div className="mode-card">
      <div className="mode-card-header">
        <ModeIcon mode={mode.gamemode}/>
        <strong>{GAMEMODE_NAMES[mode.gamemode]}</strong>
        <span className={`progress progress-${nominated}`}>{nominated}/{mode.nominators.length} nominated</span>
      </div>
      {mode.nominators.map(({nominator, hasNominated}, index) => {
        const missing = isMissing(nominator)
        return (
          <div key={index} className="nominator">
            <Avatar user={nominator} size="large"/>
            <div className="nominator-info">
              <strong className={missing ? "muted" : undefined}>{missing ? "Open slot" : nominator.username}</strong>
              {!missing && <RoleBadges user={nominator}/>}
              {!missing && (hasNominated
                ? <span className="nominated"><FaCheck/>Nominated</span>
                : <span className="muted">Not nominated yet</span>)
              }
            </div>
            {editable && !hasNominated &&
              <div className="nominator-actions">
                {missing && claimable && <button className="button button-primary" disabled={busy} onClick={onClaim}>Claim</button>}
                <button className="icon-button" disabled={busy} onClick={() => onAssign(nominator.osuId)}
                        aria-label={missing ? "Assign a nominator" : `Replace ${nominator.username}`}
                        title={missing ? "Assign a nominator" : "Replace"}>
                  {missing ? <FaUserPlus/> : <FaUserPen/>}
                </button>
                {!missing &&
                  <button className="icon-button" disabled={busy} onClick={() => onRemove(nominator.osuId)}
                          aria-label={`Remove ${nominator.username}`} title="Remove">
                    <FaTrashCan/>
                  </button>
                }
              </div>
            }
          </div>
        )
      })}
    </div>
  )
}

function Note({beatmap, editable, onSaved}: {beatmap: Beatmap, editable: boolean, onSaved: () => void}) {
  const toast = useToast()
  const [note, setNote] = useState(beatmap.note ?? "")
  const [saving, setSaving] = useState(false)

  if (!editable) {
    return (
      <section className="panel-section">
        <h3>Note</h3>
        <p className={beatmap.note ? undefined : "muted"}>{beatmap.note || "No note"}</p>
      </section>
    )
  }

  async function save() {
    setSaving(true)
    try {
      await request(Api.updateBeatmapNote(beatmap.osuId, note))
      toast("Note saved")
      onSaved()
    } catch {
      toast("Could not save the note", "error")
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="panel-section">
      <h3><label htmlFor="beatmap-note">Note</label></h3>
      <textarea id="beatmap-note" rows={3} placeholder="Add a note for the other nominators" value={note} onChange={event => setNote(event.target.value)}/>
      {note !== (beatmap.note ?? "") &&
        <div className="button-row">
          <button className="button button-primary" disabled={saving} onClick={save}>{saving ? "Saving…" : "Save note"}</button>
          <button className="button" disabled={saving} onClick={() => setNote(beatmap.note ?? "")}>Cancel</button>
        </div>
      }
    </section>
  )
}
