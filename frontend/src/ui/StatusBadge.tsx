import {BeatmapStatus} from "../models/Types";

export default function StatusBadge({status}: {status: BeatmapStatus}) {
  return <span className={`status status-${status.toLowerCase()}`}>{status}</span>
}
