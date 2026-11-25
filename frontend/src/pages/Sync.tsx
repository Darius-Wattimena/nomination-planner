import {FormEvent, useState} from "react";
import {Link} from "react-router-dom";
import {PENDING_STATUSES} from "../Constants";
import {BeatmapStatus, SyncInfo} from "../models/Types";
import Api from "../resources/Api";
import {request} from "../resources/client";
import {ConfirmDialog} from "../ui/Dialog";
import PageHeader from "../ui/PageHeader";
import {EmptyState} from "../ui/States";
import {isNat, useSession} from "../utils/session";

export default function Sync() {
  const session = useSession()

  if (!isNat(session)) {
    return (
      <EmptyState title="No permission">
        <p className="muted">Only members of the NAT can sync the planner.</p>
        <Link to="/" className="button">Back to home</Link>
      </EmptyState>
    )
  }

  return (
    <>
      <PageHeader title="Sync" subtitle="Update the planner with the latest details from osu!."/>
      <div className="cards">
        <SyncBeatmaps/>
        <SyncUsers/>
      </div>
    </>
  )
}

function useSync() {
  const [syncing, setSyncing] = useState(false)
  const [result, setResult] = useState<SyncInfo>()
  const [failed, setFailed] = useState(false)

  async function run(config: Parameters<typeof request>[0]) {
    setSyncing(true)
    setResult(undefined)
    setFailed(false)
    try {
      setResult(await request<SyncInfo>(config))
    } catch {
      setFailed(true)
    } finally {
      setSyncing(false)
    }
  }

  return {syncing, result, failed, run}
}

function SyncResult({syncing, result, failed, unit}: Omit<ReturnType<typeof useSync>, "run"> & {unit: string}) {
  return (
    <div role="status">
      {syncing && <p className="alert"><span className="spinner"/>Syncing, this can take a while…</p>}
      {failed && <p className="alert alert-error">The sync did not finish. It may still be running on the server.</p>}
      {result && <p className="alert alert-success">Synced {result.totalSynced} {unit} in {(result.duration / 1000).toFixed(1)} seconds.</p>}
    </div>
  )
}

function SyncBeatmaps() {
  const {run, ...sync} = useSync()
  const [statuses, setStatuses] = useState<BeatmapStatus[]>([])
  const [confirming, setConfirming] = useState(false)
  const everything = statuses.length === 0

  return (
    <section className="card">
      <h2>Beatmaps</h2>
      <p className="muted">Pick which pending beatmaps to sync. Nothing picked means all of them.</p>
      <div className="chips" role="group" aria-label="Statuses to sync">
        {PENDING_STATUSES.map(status => {
          const active = statuses.includes(status)
          return (
            <button
              key={status}
              className={`chip chip-status status-${status.toLowerCase()}`}
              aria-pressed={active}
              disabled={sync.syncing}
              onClick={() => setStatuses(active ? statuses.filter(it => it !== status) : [...statuses, status])}
            >
              {status}
            </button>
          )
        })}
      </div>
      <p className="alert alert-warning">Every beatmap takes at least 2 seconds. Users that are unknown to the planner are looked up too.</p>
      <SyncResult {...sync} unit="beatmap(s)"/>
      <div className="button-row">
        <button className="button button-primary" disabled={sync.syncing} onClick={() => setConfirming(true)}>
          {everything ? "Sync all pending beatmaps" : "Sync selected beatmaps"}
        </button>
        <button
          className="button"
          disabled={sync.syncing}
          onClick={() => setStatuses([BeatmapStatus.Qualified, BeatmapStatus.Nominated, BeatmapStatus.Disqualified, BeatmapStatus.Reset])}
        >
          Select active statuses
        </button>
      </div>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={async () => {
          setConfirming(false)
          void run(Api.syncBeatmaps(statuses))
        }}
        title="Sync beatmaps"
        action="Start sync"
      >
        <p>Sync {everything ? <b>all pending beatmaps</b> : <>all beatmaps that are <b>{statuses.join(", ")}</b></>}? This can take a long time.</p>
      </ConfirmDialog>
    </section>
  )
}

function SyncUsers() {
  const {run, ...sync} = useSync()
  const [text, setText] = useState("")
  const [invalid, setInvalid] = useState(false)

  function submit(event: FormEvent) {
    event.preventDefault()
    const parts = text.split(/[\s,]+/).filter(Boolean)
    const valid = parts.length > 0 && parts.every(it => /^\d+$/.test(it))
    setInvalid(!valid)
    if (valid) void run(Api.syncUsers(Array.from(new Set(parts))))
  }

  return (
    <section className="card">
      <h2>Users</h2>
      <p className="muted">Look up users on osu! again, for example after their role changed.</p>
      <form onSubmit={submit}>
        <label className="field">
          <span>User IDs, separated by a comma</span>
          <input value={text} placeholder="2369776, 318565" inputMode="numeric" aria-invalid={invalid} disabled={sync.syncing} onChange={event => setText(event.target.value)}/>
        </label>
        {invalid && <p className="field-error">Only use numeric osu! user IDs.</p>}
        <SyncResult {...sync} unit="user(s)"/>
        <button className="button button-primary" disabled={sync.syncing}>Sync users</button>
      </form>
    </section>
  )
}
