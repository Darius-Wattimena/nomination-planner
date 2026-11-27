import {useEffect, useRef} from "react";
import {useLocation, useNavigate} from "react-router";
import type {Beatmap} from "../models/Types";
import {usePlanner} from "../resources/planner";
import {notifying} from "../utils/notify";
import {describeTurn, turnChanges} from "../utils/turns";

/** The pages that open a beatmap from ?map=, the others go to the home page for it */
const OPENS_MAPS = ["/", "/beatmaps", "/discover", "/team", "/statistics", "/ranked", "/graveyard"]

/** Shows a notification for every live change that concerns the logged-in nominator, used once by the shell */
export function useTurnNotifications(userId: string | undefined) {
  const {maps} = usePlanner()
  const navigate = useNavigate()
  const location = useLocation()
  const previous = useRef<{userId?: string, maps?: Beatmap[]}>({})
  // Clicking happens later, when the page can be another one
  const where = useRef(location)
  useEffect(() => {
    where.current = location
  })

  useEffect(() => {
    const before = previous.current
    previous.current = {userId, maps}
    if (before.userId !== userId || !notifying()) return

    for (const change of turnChanges(before.maps, maps, userId)) {
      const {title, body} = describeTurn(change)
      const notification = new Notification(title, {body, tag: `${change.kind}-${change.map.osuId}`, icon: `https://b.ppy.sh/thumb/${change.map.osuId}l.jpg`})
      notification.onclick = () => {
        window.focus()
        notification.close()
        const {pathname, search} = where.current
        const params = new URLSearchParams(OPENS_MAPS.includes(pathname) ? search : "")
        params.set("map", change.map.osuId)
        void navigate({pathname: OPENS_MAPS.includes(pathname) ? pathname : "/", search: `?${params}`})
      }
    }
  }, [maps, userId, navigate])
}
