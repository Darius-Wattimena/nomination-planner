import {useEffect} from "react";

/** Sets the title of the tab. Without a title it leaves it to what is shown inside, which then sets its own. */
export function useTitle(title: string | undefined) {
  useEffect(() => {
    if (title) document.title = `${title} · Nomination Planner`
  }, [title])
}
