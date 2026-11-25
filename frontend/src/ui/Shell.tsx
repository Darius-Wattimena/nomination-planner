import {ReactNode} from "react";
import {Link, NavLink, Outlet} from "react-router-dom";
import {FaArrowsRotate, FaBoxArchive, FaHouse, FaMoon, FaMusic, FaRightFromBracket, FaSun, FaTrophy} from "react-icons/fa6";
import logo from "../assets/greaper.png";
import {isNat, setSession, useSession} from "../utils/session";
import {useTheme} from "../utils/theme";
import {Avatar, RoleBadges} from "./Person";
import {LoginButton} from "./States";

export default function Shell() {
  const session = useSession()
  const user = session?.user

  return (
    <div className="shell">
      <a className="skip-link" href="#content">Skip to content</a>
      <aside className="sidebar">
        <Link to="/" className="brand">
          <img src={logo} alt=""/>
          <span>Nomination<br/>Planner</span>
        </Link>
        <nav className="nav" aria-label="Main">
          <Item to="/" icon={<FaHouse/>} label="Home"/>
          {user && <>
            <Item to="/beatmaps" icon={<FaMusic/>} label="Pending"/>
            <Item to="/ranked" icon={<FaTrophy/>} label="Ranked"/>
            <Item to="/graveyard" icon={<FaBoxArchive/>} label="Graveyard"/>
            {isNat(session) && <Item to="/admin" icon={<FaArrowsRotate/>} label="Sync"/>}
          </>}
        </nav>
        <div className="account">
          {user
            ? <div className="account-user">
                <Avatar user={user} size="large"/>
                <div className="account-name"><strong>{user.username}</strong><RoleBadges user={user}/></div>
              </div>
            : <LoginButton/>
          }
          <div className="account-actions">
            <ThemeButton/>
            {user &&
              <button className="icon-button" aria-label="Log out" title="Log out" onClick={() => setSession(undefined)}>
                <FaRightFromBracket/>
              </button>
            }
          </div>
        </div>
      </aside>
      <main id="content" className="content" tabIndex={-1}><Outlet/></main>
    </div>
  )
}

function Item({to, icon, label}: {to: string, icon: ReactNode, label: string}) {
  return (
    <NavLink to={to} end className={({isActive}) => isActive ? "nav-item active" : "nav-item"}>
      {icon}<span>{label}</span>
    </NavLink>
  )
}

function ThemeButton() {
  const {theme, toggle} = useTheme()
  const label = theme === "dark" ? "Use light theme" : "Use dark theme"
  return (
    <button className="icon-button" aria-label={label} title={label} onClick={toggle}>
      {theme === "dark" ? <FaSun/> : <FaMoon/>}
    </button>
  )
}
