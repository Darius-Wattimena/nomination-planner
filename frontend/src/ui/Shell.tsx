import type {ReactNode} from "react";
import {Link, NavLink, Outlet} from "react-router";
import {FaBoxArchive, FaChartColumn, FaHeadphones, FaHouse, FaMagnifyingGlass, FaMoon, FaPeopleGroup, FaShieldHalved, FaSun, FaTableColumns} from "react-icons/fa6";
import logo from "../assets/greaper.png";
import {usePlanner, usePlannerRefresh} from "../resources/planner";
import {canEdit, isAdmin, useSession} from "../utils/session";
import {useTheme} from "../utils/theme";
import AccountMenu from "./AccountMenu";
import {useFind} from "./useFind";
import {LoginButton} from "./States";
import {involvementOf} from "../utils/involvement";

export default function Shell() {
  const session = useSession()
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
            <Item to="/beatmaps" icon={<FaTableColumns/>} label="Beatmaps"/>
            {canEdit(session) && <Item to="/discover" icon={<FaHeadphones/>} label="Discover"/>}
            <Item to="/team" icon={<FaPeopleGroup/>} label="Nominators"/>
            <Item to="/statistics" icon={<FaChartColumn/>} label="Stats"/>
            <Item to="/ranked" icon={<FaBoxArchive/>} label="Archive"/>
            {isAdmin(session) && <Item to="/admin" icon={<FaShieldHalved/>} label="Admin" className="nav-admin"/>}
          </nav>
        }
        <div className="topbar-end">
          {user
            ? <>
                {isAdmin(session) && <NavLink to="/admin" className={({isActive}) => isActive ? "search-button admin-button active" : "search-button admin-button"}><FaShieldHalved aria-hidden/>Admin</NavLink>}
                <button className="search-button" aria-label="Find a beatmap or nominator" onClick={openFind}><FaMagnifyingGlass aria-hidden/><span>Find</span></button>
                <AccountMenu user={user}/>
              </>
            : <>
                <button className="icon-button" aria-label={themeLabel} title={themeLabel} onClick={toggle}>{theme === "dark" ? <FaSun/> : <FaMoon/>}</button>
                <LoginButton/>
              </>
          }
        </div>
      </header>
      <main id="content" className="content" tabIndex={-1}><Outlet/></main>
    </div>
  )
}

function Item({to, icon, label, badge, className = ""}: {to: string, icon: ReactNode, label: string, badge?: number, className?: string}) {
  return (
    <NavLink to={to} end className={({isActive}) => `nav-item ${className} ${isActive ? "active" : ""}`}>
      {icon}<span>{label}</span>{!!badge && <span className="nav-badge" aria-label={`${badge} waiting for you`}>{badge}</span>}
    </NavLink>
  )
}
