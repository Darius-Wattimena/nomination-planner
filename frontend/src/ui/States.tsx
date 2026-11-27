import type {ReactNode} from "react";
import {useState} from "react";
import {startLogin} from "../utils/login";
import {reloadPlanner, usePlanner} from "../resources/planner";
import {loadSession} from "../resources/client";

export function Loading() {
  return <div className="state" role="status"><span className="spinner"/>Loading…</div>
}

export function EmptyState({title, children}: {title: string, children?: ReactNode}) {
  return (
    <div className="state">
      <h2>{title}</h2>
      {children}
    </div>
  )
}

export function LoginButton({returnTo}: {returnTo?: string}) {
  return <button className="button button-osu" onClick={() => startLogin(returnTo)}>Log in with osu!</button>
}

/** Shown until the planner is there, or when it could not be refreshed */
export function PlannerStatus({rows = 4}: {rows?: number}) {
  const {maps, error} = usePlanner()
  if (error) return <p className="alert alert-error" role="alert">Could not refresh the planner. <button className="link" onClick={() => void reloadPlanner()}>Try again</button></p>
  if (maps) return null
  return <ul className="map-list" aria-busy aria-label="Loading">{Array.from({length: rows}, (_, it) => <li key={it} className="map-row skeleton"/>)}</ul>
}

/** Who is logged in is unknown while the backend can't be reached, so asking to log in would be wrong */
export function Unreachable() {
  const [retrying, setRetrying] = useState(false)
  return (
    <EmptyState title="Can't reach the planner">
      <p className="muted">Check your connection. The planner may also be down for a moment.</p>
      <button className="button button-primary" disabled={retrying} onClick={() => {
        setRetrying(true)
        void loadSession().finally(() => setRetrying(false))
      }}>{retrying ? "Trying again…" : "Try again"}</button>
    </EmptyState>
  )
}
