import type {ReactNode} from "react";
import {startLogin} from "../utils/login";

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
