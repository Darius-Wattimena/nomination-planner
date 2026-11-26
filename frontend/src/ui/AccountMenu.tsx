import {useEffect, useRef, useState} from "react";
import {Link} from "react-router";
import {FaCircleQuestion, FaMoon, FaRightFromBracket, FaSun} from "react-icons/fa6";
import type {User} from "../models/Types";
import {logOut} from "../resources/planner";
import {useTheme} from "../utils/theme";
import {useHelp} from "./Help";
import {Avatar} from "./Person";

/** Everything that isn't needed all day */
export default function AccountMenu({user}: {user: User}) {
  const openHelp = useHelp()
  const {theme, toggle} = useTheme()
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const outside = (event: Event) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener("pointerdown", outside)
    document.addEventListener("focusin", outside)
    return () => {
      document.removeEventListener("pointerdown", outside)
      document.removeEventListener("focusin", outside)
    }
  }, [open])

  return (
    <div className="account" ref={root} onKeyDown={event => {
      if (event.key !== "Escape" || !open) return
      setOpen(false)
      button.current?.focus()
    }}>
      <button ref={button} className="me" aria-expanded={open} aria-label={`Menu of ${user.username}`} onClick={() => setOpen(!open)}><Avatar user={user}/></button>
      {open &&
        <nav className="popup account-menu" aria-label="Account" onClick={() => setOpen(false)}>
          <Link className="account-profile" to={`/team?user=${user.osuId}`}><Avatar user={user} size="large"/><span><strong>{user.username}</strong><span className="muted">Your profile</span></span></Link>
          <button onClick={toggle}>{theme === "dark" ? <FaSun/> : <FaMoon/>}{theme === "dark" ? "Light theme" : "Dark theme"}</button>
          <button onClick={openHelp}><FaCircleQuestion/>Help</button>
          <button onClick={() => void logOut()}><FaRightFromBracket/>Log out</button>
        </nav>
      }
    </div>
  )
}
