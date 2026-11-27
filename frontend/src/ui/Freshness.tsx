import {useEffect, useState} from "react";
import {reloadPlanner, usePlanner} from "../resources/planner";

/** How recent everything on the page is */
export default function Freshness() {
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
