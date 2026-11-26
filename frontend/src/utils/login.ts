import {CONFIG} from "../Settings";

/** Log in with osu! through the backend, which sends the browser back to this page afterwards */
export function startLogin(returnTo: string = "/") {
  const params = new URLSearchParams({returnTo})
  window.location.href = `${CONFIG.api_url}oauth2/authorization/osu?${params}`
}
