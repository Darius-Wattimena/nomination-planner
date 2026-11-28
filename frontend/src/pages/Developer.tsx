import {useEffect, useState} from "react";
import type {HistoryStatus, ImportStatus, ImportSummary} from "../models/Types";
import Api from "../resources/Api";
import {problemOf, request} from "../resources/client";
import {ConfirmDialog} from "../ui/Dialog";

type Config = Parameters<typeof request>[0]

/** Asks again every few seconds while it is running, and once whenever refreshed */
function usePolled<T extends {running: boolean}>(config: () => Config, every: number) {
  const [value, setValue] = useState<T>()
  const [asked, setAsked] = useState(0)

  useEffect(() => {
    let stopped = false
    let timer: number | undefined

    async function check() {
      try {
        const found = await request<T>(config())
        if (stopped) return
        setValue(found)
        if (found.running) timer = window.setTimeout(check, every)
      } catch {
        if (!stopped) timer = window.setTimeout(check, every)
      }
    }

    void check()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [asked, config, every])

  return {value, refresh: () => setAsked(it => it + 1)}
}

/** Only for the developers: moving ranked beatmaps between planners, and importing the ranked history of osu! */
export default function Developer() {
  return (
    <div className="cards">
      <ExportImport/>
      <RankedHistory/>
    </div>
  )
}

function ExportImport() {
  const status = usePolled<ImportStatus>(Api.fetchImportStatus, 3000)
  const [exporting, setExporting] = useState(false)
  const [file, setFile] = useState<File>()
  const [checking, setChecking] = useState(false)
  const [summary, setSummary] = useState<ImportSummary>()
  const [failed, setFailed] = useState<string>()
  const [confirming, setConfirming] = useState(false)
  const running = !!status.value?.running
  const last = status.value?.last

  async function download() {
    setExporting(true)
    setFailed(undefined)
    try {
      const blob = await request<Blob>(Api.exportRanked())
      const link = document.createElement("a")
      link.href = URL.createObjectURL(blob)
      link.download = `planner-ranked-${new Date().toISOString().slice(0, 10)}.json.gz`
      link.click()
      URL.revokeObjectURL(link.href)
    } catch (error) {
      setFailed(problemOf(error) ?? "The export could not be made.")
    } finally {
      setExporting(false)
    }
  }

  async function check(chosen: File | undefined) {
    setFile(chosen)
    setSummary(undefined)
    setFailed(undefined)
    if (!chosen) return
    setChecking(true)
    try {
      setSummary(await request<ImportSummary>(Api.importRanked(chosen, true)))
    } catch (error) {
      setFailed(problemOf(error) ?? "The file could not be read.")
    } finally {
      setChecking(false)
    }
  }

  async function start() {
    if (!file) return
    setFailed(undefined)
    try {
      await request<ImportSummary>(Api.importRanked(file, false))
      setSummary(undefined)
      setFile(undefined)
      status.refresh()
    } catch (error) {
      setFailed(problemOf(error) ?? "The import could not be started.")
    }
  }

  const nothingNew = !summary || summary.newBeatmaps + summary.newUsers + summary.roleChanges === 0

  return (
    <section className="card">
      <h2>Export and import ranked beatmaps</h2>
      <p className="muted">Only for developers. Moves ranked beatmaps with their history, the users they mention and the role history between planners, like from your own machine to the servers. Only ranked beatmaps that are new are added, everything else stays as it is.</p>
      <div className="button-row">
        <button className="button" disabled={exporting} onClick={() => void download()}>{exporting ? "Exporting…" : "Export ranked beatmaps"}</button>
      </div>
      <label className="field">
        <span>Export to import</span>
        <input type="file" accept=".gz,.json,application/gzip,application/json" disabled={checking || running} onChange={event => void check(event.target.files?.[0])}/>
      </label>
      <div role="status">
        {checking && <p className="alert"><span className="spinner"/>Reading the file…</p>}
        {failed && <p className="alert alert-error">{failed}</p>}
        {summary && <ImportResult summary={summary} title="Importing it would add"/>}
        {running && <p className="alert"><span className="spinner"/>Importing…</p>}
        {!running && status.value?.failed && <p className="alert alert-error">{status.value.failed}</p>}
        {!running && !status.value?.failed && last && !summary && <ImportResult summary={last} title="The last import added"/>}
      </div>
      <div className="button-row">
        <button className="button button-primary" disabled={nothingNew || running} onClick={() => setConfirming(true)}>Import</button>
      </div>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={async () => {
          setConfirming(false)
          void start()
        }}
        title="Import ranked beatmaps"
        action="Start import"
      >
        <p>Add <b>{summary?.newBeatmaps ?? 0} ranked beatmaps</b> and <b>{summary?.newUsers ?? 0} users</b> to this planner? What it has already stays as it is.</p>
      </ConfirmDialog>
    </section>
  )
}

function ImportResult({summary, title}: {summary: ImportSummary, title: string}) {
  return (
    <div className="alert">
      <p>{title}, from the export of {new Date(summary.exportedAt).toLocaleString("en-GB")}:</p>
      <ul>
        <li>{summary.newBeatmaps} ranked beatmaps with {summary.events} history entries ({summary.knownBeatmaps} are here already)</li>
        <li>{summary.newUsers} users ({summary.knownUsers} are known already)</li>
        <li>{summary.roleChanges} role changes, leaving out those known already</li>
        {summary.notRanked > 0 && <li>{summary.notRanked} beatmaps that are not ranked, which are never imported</li>}
      </ul>
    </div>
  )
}

const PHASES = {Roles: "Reading the role history", Beatmaps: "Importing ranked beatmaps", Modes: "Working out the modes"}

function RankedHistory() {
  const status = usePolled<HistoryStatus>(Api.fetchHistoryStatus, 5000)
  const [failed, setFailed] = useState<string>()
  const value = status.value
  const running = !!value?.running

  async function call(config: Config) {
    setFailed(undefined)
    try {
      await request<HistoryStatus>(config)
    } catch (error) {
      setFailed(problemOf(error) ?? "That did not work.")
    } finally {
      status.refresh()
    }
  }

  function describe(it: HistoryStatus) {
    if (it.running) return it.phase ? PHASES[it.phase] : "Starting"
    if (it.done) return "Every ranked beatmap of osu! was seen"
    return it.seen > 0 ? "Stopped, it continues where it was" : "Not started yet"
  }

  return (
    <section className="card">
      <h2>Ranked history of osu!</h2>
      <p className="muted">Only for developers. Imports every ranked beatmap of osu! with its nominators, about 60,000, along with the staff log of the wiki and the group history of osu!. It asks osu! about each one, which takes about 17 hours, so run it on your own machine and import its export on the servers. It continues where it stopped.</p>
      <div role="status">
        {failed && <p className="alert alert-error">{failed}</p>}
        {value && (
          <ul>
            <li>{running && <span className="spinner"/>}{describe(value)}</li>
            <li>{value.seen} ranked beatmaps seen, {value.imported} imported{value.reached && `, up to those ranked on ${new Date(value.reached).toLocaleDateString("en-GB")}`}</li>
            {value.guessed > 0 && <li>The modes of {value.guessed} beatmaps were worked out from other nominations</li>}
            {value.failed && <li className="field-error">{value.failed}</li>}
          </ul>
        )}
      </div>
      <div className="button-row">
        <button className="button button-primary" disabled={!value || running} onClick={() => void call(Api.startHistory())}>{value && value.seen > 0 && !value.done ? "Continue" : "Start"}</button>
        <button className="button" disabled={!running} onClick={() => void call(Api.stopHistory())}>Stop</button>
        <button className="button" disabled={!value || running || !value.done} onClick={() => void call(Api.guessHistoryModes())}>Work out modes again</button>
      </div>
    </section>
  )
}
