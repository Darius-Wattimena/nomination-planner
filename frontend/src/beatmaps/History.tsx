import {useState} from "react";
import type {BeatmapEvent} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import {exactTime} from "../utils/workflow";
import {describeEvent, HISTORY_START, timeAgo} from "./historyText";
import "./mapHistory.css";

/** What happened to the beatmap, only asked for once someone opens it */
export default function History({id}: {id: string}) {
  const [opened, setOpened] = useState(false)
  const {data, loading, error, reload} = useQuery<BeatmapEvent[]>(opened ? Api.fetchBeatmapEvents(id) : undefined)
  const start = new Date(HISTORY_START).toLocaleDateString("en-GB", {dateStyle: "long"})

  return (
    <details className="map-history" onToggle={event => {
      if (!(event.currentTarget as HTMLDetailsElement).open) return
      // Opening it again shows what happened in the meantime
      if (opened) reload()
      else setOpened(true)
    }}>
      <summary><h3>History</h3></summary>
      {error
        ? <p className="muted">Could not load the history. <button className="link" onClick={reload}>Try again</button></p>
        : !data && loading
          ? <p className="muted">Loading…</p>
          : data?.length
            ? <ol className="history-list">
                {data.map((event, index) => (
                  <li key={index}>
                    <span>{describeEvent(event)}</span>
                    <time className="muted" dateTime={String(event.at)} title={exactTime(event.at)}>{timeAgo(event.at)}</time>
                  </li>
                ))}
              </ol>
            : <p className="muted">Nothing yet. The history starts from {start}.</p>
      }
      {!!data?.length && data.length >= 50 && <p className="muted history-more">Only the newest 50 are shown.</p>}
      {!!data?.length && <p className="muted history-more">The history starts from {start}.</p>}
    </details>
  )
}
