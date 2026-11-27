import type {FormEvent} from "react";
import {useEffect, useState} from "react";
import {Link} from "react-router";
import {PENDING_STATUSES} from "../Constants";
import type {SyncQueued} from "../models/Types";
import {BeatmapStatus} from "../models/Types";
import Api from "../resources/Api";
import {problemOf, request} from "../resources/client";
import {ConfirmDialog} from "../ui/Dialog";
import Health from "../stats/Health";
import PageHeader from "../ui/PageHeader";
import {useTitle} from "../ui/useTitle";
import {EmptyState} from "../ui/States";
import {isAdmin, useSession} from "../utils/session";

export default function Admin() {
  const session = useSession()
  // Every sync that starts has something new to wait for
  const [started, setStarted] = useState(0)
  const onStarted = () => setStarted(it => it + 1)
  const allowed = isAdmin(session)
  useTitle(allowed ? undefined : "No permission")

  if (!allowed) {
    return (
      <div className="page">
        <EmptyState title="No permission">
          <p className="muted">This page is for the NAT.</p>
          <Link to="/" className="button">Back to home</Link>
        </EmptyState>
      </div>
    )
  }

  return (
    <div className="page">
      <PageHeader title="Admin" subtitle="Only visible to the NAT"/>
      <Health/>
      <SyncWaiting key={started}/>
      <div className="cards">
        <SyncBeatmaps onStarted={onStarted}/>
        <SyncUsers onStarted={onStarted}/>
      </div>
    </div>
  )
}

function useSync(onStarted: () => void) {
  const [syncing, setSyncing] = useState(false)
  const [result, setResult] = useState<SyncQueued>()
  const [failed, setFailed] = useState<string>()

  async function run(config: Parameters<typeof request>[0]) {
    setSyncing(true)
    setResult(undefined)
    setFailed(undefined)
    try {
      setResult(await request<SyncQueued>(config))
      onStarted()
    } catch (error) {
      setFailed(problemOf(error) ?? "The sync could not be started.")
    } finally {
      setSyncing(false)
    }
  }

  return {syncing, result, failed, run}
}

/** @param unit what is synced, in the singular */
function SyncResult({syncing, result, failed, unit}: Omit<ReturnType<typeof useSync>, "run"> & {unit: "beatmap" | "user"}) {
  return (
    <div role="status">
      {syncing && <p className="alert"><span className="spinner"/>Starting the sync…</p>}
      {failed && <p className="alert alert-error">{failed}</p>}
      {result && <p className="alert alert-success">{result.queued} {result.queued === 1 ? unit : `${unit}s`} will be synced in the background. {result.queued === 1 ? "It changes on the planner once that is done." : "They change on the planner one by one."}</p>}
    </div>
  )
}

function SyncBeatmaps({onStarted}: {onStarted: () => void}) {
  const {run, ...sync} = useSync(onStarted)
  const [statuses, setStatuses] = useState<BeatmapStatus[]>([])
  const [confirming, setConfirming] = useState(false)
  const everything = statuses.length === 0

  return (
    <section className="card">
      <h2>Sync beatmaps</h2>
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
      <SyncResult {...sync} unit="beatmap"/>
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
        <p>Sync {everything ? <b>all pending beatmaps</b> : <>all beatmaps that are <b>{statuses.join(", ")}</b></>}? It runs in the background, the planner can be used meanwhile.</p>
      </ConfirmDialog>
    </section>
  )
}

/** The beatmaps and users still waiting to be synced in the background. Stops asking once there are none, until the next sync. */
function SyncWaiting() {
  const [waiting, setWaiting] = useState<number>()

  useEffect(() => {
    let stopped = false
    let busy = false
    let done = false
    let timer: number | undefined

    async function check() {
      window.clearTimeout(timer)
      // Nobody looks at a hidden page, it asks again once it is visible
      if (stopped || busy || done || document.hidden) return
      busy = true
      try {
        const count = await request<number>(Api.fetchSyncWaiting())
        if (stopped) return
        setWaiting(count)
        done = count === 0
      } catch {
        // Asked again in a moment
      } finally {
        busy = false
      }
      if (!done && !stopped) timer = window.setTimeout(check, 3000)
    }

    const onVisible = () => void check()
    void check()
    document.addEventListener("visibilitychange", onVisible)
    return () => {
      stopped = true
      window.clearTimeout(timer)
      document.removeEventListener("visibilitychange", onVisible)
    }
  }, [])

  return waiting ? <p className="muted" role="status">{waiting} still waiting to be synced.</p> : null
}

function SyncUsers({onStarted}: {onStarted: () => void}) {
  const {run, ...sync} = useSync(onStarted)
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
      <h2>Sync users</h2>
      <p className="muted">Look up users on osu! again, for example after their role changed.</p>
      <form onSubmit={submit}>
        <label className="field">
          <span>osu! user IDs, separated by commas or spaces</span>
          <input value={text} placeholder="2369776, 318565" inputMode="numeric" aria-invalid={invalid} disabled={sync.syncing} onChange={event => setText(event.target.value)}/>
        </label>
        {invalid && <p className="field-error">Only use numeric osu! user IDs.</p>}
        <SyncResult {...sync} unit="user"/>
        <button className="button button-primary" disabled={sync.syncing}>Sync users</button>
      </form>
    </section>
  )
}
