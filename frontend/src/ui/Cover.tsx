import {useState} from "react";

/** Not every beatmap has a cover, fall back to the background of the container */
export default function Cover({id, size}: {id: string, size: "card" | "cover"}) {
  const [failed, setFailed] = useState(false)
  if (failed) return null
  return <img src={`https://assets.ppy.sh/beatmaps/${id}/covers/${size}.jpg`} alt="" loading="lazy" onError={() => setFailed(true)}/>
}
