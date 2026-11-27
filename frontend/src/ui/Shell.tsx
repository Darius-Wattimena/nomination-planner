import type {ReactNode} from "react";
import {Link, NavLink, Outlet} from "react-router";
import {FaBoxArchive, FaChartColumn, FaHeadphones, FaHouse, FaMagnifyingGlass, FaMoon, FaPeopleGroup, FaShieldHalved, FaSun, FaTableColumns} from "react-icons/fa6";
import logo from "../assets/greaper.png";
import {usePlanner, usePlannerRefresh} from "../resources/planner";
import {canEdit, isAdmin, useSession, useSessionKnown, useSessionUnreachable} from "../utils/session";
import {useTheme} from "../utils/theme";
import AccountMenu from "./AccountMenu";
import {useFind} from "./useFind";
import {LoginButton} from "./States";
import {involvementOf} from "../utils/involvement";

export default function Shell() {
  const session = useSession()
  const known = useSessionKnown()
  const unreachable = useSessionUnreachable()
  const user = session?.user
  const openFind = useFind()
  const {theme, toggle} = useTheme()
  const {maps} = usePlanner()
  usePlannerRefresh()

  const moves = involvementOf(maps ?? [], user?.osuId).filter(it => it.part === "move").length
  const themeLabel = theme === "dark" ? "Use the light theme" : "Use the dark theme"

  return (
    <div className="shell">
      <a className="skip-link" href="#content">Skip to content</a>
      <header className="topbar">
        <Link to="/" className="brand"><img src={logo} alt=""/><span>Nomination Planner</span></Link>
        {user &&
          <nav className="nav" aria-label="Main">
            <Item to="/" icon={<FaHouse/>} label="Home" badge={moves}/>
            <Item to="/beatmaps" icon={<FaTableColumns/>} label="Beatmaps" short="Maps"/>
            {canEdit(session) && <Item to="/discover" icon={<FaHeadphones/>} label="Discover"/>}
            <Item to="/team" icon={<FaPeopleGroup/>} label="Nominators" short="Team"/>
            <Item to="/statistics" icon={<FaChartColumn/>} label="Stats"/>
            <Item to="/ranked" icon={<FaBoxArchive/>} label="Archive"/>
          </nav>
        }
        <div className="topbar-end">
          {user
            ? <>
                {isAdmin(session) && <NavLink to="/admin" aria-label="Admin" className={({isActive}) => `search-button admin-button ${isActive ? "active" : ""}`}><FaShieldHalved aria-hidden/><span>Admin</span></NavLink>}
                <button className="search-button" aria-label="Find a beatmap or nominator" onClick={openFind}><FaMagnifyingGlass aria-hidden/><span>Find</span></button>
                <AccountMenu user={user}/>
              </>
            : <>
                <button className="icon-button" aria-label={themeLabel} title={themeLabel} onClick={toggle}>{theme === "dark" ? <FaSun/> : <FaMoon/>}</button>
                {known && !unreachable && <LoginButton/>}
              </>
          }
        </div>
      </header>
      <main id="content" className="content" tabIndex={-1}><Outlet/></main>
    </div>
  )
}

/** On a narrow phone the short label is shown, so every item fits next to each other */
function Item({to, icon, label, short = label, badge}: {to: string, icon: ReactNode, label: string, short?: string, badge?: number}) {
  return (
    <NavLink to={to} end className={({isActive}) => `nav-item ${isActive ? "active" : ""}`}>
      {icon}<span className="nav-label">{label}</span>{short !== label && <span className="nav-short" aria-hidden>{short}</span>}{!!badge && <span className="nav-badge" aria-label={`${badge} waiting for you`}>{badge}</span>}
    </NavLink>
  )
}
