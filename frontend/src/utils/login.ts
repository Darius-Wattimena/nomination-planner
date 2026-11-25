import {CONFIG} from "../Settings";

const KEY = "osuLogin"

export function startLogin(returnTo: string = "/") {
  const state = crypto.randomUUID()
  sessionStorage.setItem(KEY, JSON.stringify({state, returnTo}))

  const params = new URLSearchParams({
    response_type: "code",
    client_id: String(CONFIG.osu_id),
    redirect_uri: CONFIG.osu_redirect,
    scope: "identify public",
    state
  })
  window.location.href = `https://osu.ppy.sh/oauth/authorize?${params}`
}

/** Returns the page to go back to, or undefined when this login was not started from this browser tab */
export function finishLogin(state: string | null): string | undefined {
  try {
    const stored = JSON.parse(sessionStorage.getItem(KEY) ?? "null")
    sessionStorage.removeItem(KEY)
    return state && stored?.state === state ? stored.returnTo : undefined
  } catch {
    return undefined
  }
}
