import type {ReactNode} from "react";
import {useEffect, useState} from "react";
import {reloadPlanner, usePlanner} from "../resources/planner";
import Dialog from "./Dialog";
import {HelpContext} from "./useHelp";

export function HelpProvider({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)

  return (
    <HelpContext.Provider value={() => setOpen(true)}>
      {children}
      <Dialog open={open} onClose={() => setOpen(false)} title="What the circles and colors mean"><Help/></Dialog>
    </HelpContext.Provider>
  )
}

function Help() {
  return (
    <div className="dialog-body help">
      <section>
        <h3>The circles</h3>
        <p className="muted">One for every mode. The main mode has two halves, as it needs two nominations. Other modes of a hybrid need one.</p>
        <RingLegend/>
      </section>
      <section>
        <h3>The colors</h3>
        <ul className="help-colors">
          <li><span className="step step-you">Waiting for you</span></li>
          <li><span className="step step-open">Needs a nominator</span></li>
          <li><span className="step step-warn">Disqualified or reset</span></li>
          <li><span className="step step-good">Nominated, qualified or ranked</span></li>
          <li><span className="step step-calm">On its way, nothing for you to do</span></li>
        </ul>
      </section>
    </div>
  )
}

export function RingLegend() {
  return (
    <span className="ring-legend">
      {(["open", "assigned", "done"] as const).map(it => (
        <span key={it}>
          <svg viewBox="0 0 24 40" aria-hidden><path className={`arc arc-${it}`} d="M21 37 A18 18 0 0 1 21 3"/></svg>
          {{open: "open spot", assigned: "planned", done: "nominated"}[it]}
        </span>
      ))}
    </span>
  )
}

/** How recent everything on the page is */
export function Freshness() {
  const {loadedAt, refreshing, error} = usePlanner()
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 20_000)
    return () => window.clearInterval(timer)
  }, [])

  if (!loadedAt) return null
  const minutes = Math.floor((Math.max(now, loadedAt) - loadedAt) / 60_000)
  return (
    <span className="freshness">
      {refreshing ? "Refreshing…" : minutes === 0 ? error ? "Not refreshed" : "Up to date" : `Updated ${minutes} min ago`}
      {!refreshing && <button className="link" onClick={() => void reloadPlanner()}>Refresh</button>}
    </span>
  )
}
