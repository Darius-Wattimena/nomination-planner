import type {ReactNode} from "react";
import {useState} from "react";
import {STAGES, LABELS, OLD_DAYS, STALE_DAYS} from "../utils/workflow";
import {MODE_WORDS} from "../utils/query";
import {PENDING_STATUSES} from "../Constants";
import Dialog from "./Dialog";
import {HelpContext} from "./useHelp";

export function HelpProvider({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)

  return (
    <HelpContext.Provider value={() => setOpen(true)}>
      {children}
      <Dialog open={open} onClose={() => setOpen(false)} title="How the planner works"><Help/></Dialog>
    </HelpContext.Provider>
  )
}

const list = (values: string[]) => values.map((it, index) => <span key={it}>{index > 0 && ", "}<code>{it}</code></span>)

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
          <li><span className="step step-you">{LABELS.move}</span></li>
          <li><span className="step step-open">{LABELS.open}</span></li>
          <li><span className="step step-warn">Disqualified or reset</span></li>
          <li><span className="step step-good">Nominated, qualified or ranked</span></li>
          <li><span className="step step-calm">On its way, nothing for you to do</span></li>
        </ul>
      </section>
      <section>
        <h3>Old beatmaps</h3>
        <p className="muted">
          An hourglass means {LABELS.stale.toLowerCase()}: nothing changed for {STALE_DAYS} days.
          Beatmaps nobody changed for {OLD_DAYS} days are left out of the list, unless you map or nominate them. Choose "Include them" or "Include older ones" to see them anyway.
        </p>
      </section>
      <section>
        <h3>Searching</h3>
        <p className="muted">Words search the title, artist, mapper and note. Add any of these, like <code>mode:catch is:open</code>. The same key twice means either one, everything else has to match. Put quotes around a value with spaces.</p>
        <dl className="help-keys">
          <dt><code>bn:</code></dt><dd>A nominator, by name or id. <code>bn:me</code> is you.</dd>
          <dt><code>by:</code></dt><dd>The mapper, by name or id. <code>by:me</code> is you.</dd>
          <dt><code>mode:</code></dt><dd>{list(Object.values(MODE_WORDS))}</dd>
          <dt><code>is:</code></dt>
          <dd><code>open</code> {LABELS.open.toLowerCase()}, <code>mine</code> you nominate it, <code>move</code> {LABELS.move.toLowerCase()}, <code>stale</code> {LABELS.stale.toLowerCase()}, <code>noted</code> has a note</dd>
          <dt><code>stage:</code></dt><dd>{list(STAGES.map(it => it.id))}</dd>
          <dt><code>status:</code></dt><dd>{list(PENDING_STATUSES.map(it => it.toLowerCase()))}</dd>
        </dl>
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
          {{open: "open spot", assigned: LABELS.assigned.toLowerCase(), done: "nominated"}[it]}
        </span>
      ))}
    </span>
  )
}
