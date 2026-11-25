import {Link, useLocation} from "react-router-dom";
import logo from "../assets/greaper.png";
import {GAMEMODES} from "../Constants";
import {BeatmapFilter, BeatmapStatus, User} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import PageHeader from "../ui/PageHeader";
import {LoginButton} from "../ui/States";
import {canEdit, useSession} from "../utils/session";

export default function Home() {
  const session = useSession()
  return session?.user
    ? <Dashboard user={session.user} nominator={canEdit(session)}/>
    : <Welcome/>
}

function Welcome() {
  const loginFailed = (useLocation().state as {loginFailed?: boolean} | null)?.loginFailed

  return (
    <div className="welcome">
      <img src={logo} alt=""/>
      <h1>Nomination Planner</h1>
      <p>Plan, claim and follow beatmap nominations together with the other nominators.</p>
      <LoginButton/>
      {loginFailed &&
        <p className="alert alert-error" role="alert">
          Something went wrong while logging in with osu!. Please try again, and contact Greaper if this keeps happening.
        </p>
      }
      <p className="muted">
        By <a href="https://osu.ppy.sh/users/2369776">Greaper</a>, for{" "}
        <a href="https://osu.ppy.sh/wiki/en/People/Beatmap_Nominators">Beatmap Nominators</a>
      </p>
    </div>
  )
}

function Dashboard({user, nominator}: {user: User, nominator: boolean}) {
  const modes = GAMEMODES.filter(mode => user.gamemodes.some(it => it.gamemode === mode))
  const modeParam = modes.join(",") || "all"
  const missingNominator = modes.length > 0 ? modes : GAMEMODES

  return (
    <>
      <PageHeader title={`Welcome back, ${user.username}`} subtitle="Here is what is happening on the planner."/>
      <div className="tiles">
        {nominator &&
          <Tile
            label="My icons" hint="Pending beatmaps you are nominating"
            to={`/beatmaps?mode=all&nominators=${user.osuId}`}
            filter={{nominators: [user.osuId]}}
          />
        }
        <Tile
          label="Needs nominator" hint="Beatmaps with an open slot"
          to={`/beatmaps?mode=${modeParam}&missing=1`}
          filter={{gamemodes: modes, missingNominator}}
        />
        <Tile
          label="Nominated" hint="Waiting to be qualified"
          to={`/beatmaps?mode=${modeParam}&status=${BeatmapStatus.Nominated}`}
          filter={{gamemodes: modes, status: [BeatmapStatus.Nominated]}}
        />
        <Tile
          label="Qualified" hint="On their way to be ranked"
          to={`/beatmaps?mode=${modeParam}&status=${BeatmapStatus.Qualified}`}
          filter={{gamemodes: modes, status: [BeatmapStatus.Qualified]}}
        />
      </div>
      <div className="button-row">
        <Link className="button button-primary" to="/beatmaps">All pending beatmaps</Link>
        <Link className="button" to="/ranked">Ranked</Link>
        <Link className="button" to="/graveyard">Graveyard</Link>
      </div>
    </>
  )
}

interface TileProps {
  label: string
  hint: string
  to: string
  filter: Partial<BeatmapFilter>
}

function Tile({label, hint, to, filter}: TileProps) {
  const {data, error} = useQuery<number>(Api.countBeatmaps({...filter, page: "PENDING"}))

  return (
    <Link className="tile" to={to}>
      <span className="tile-label">{label}</span>
      <span className="tile-value">{data ?? (error ? "–" : "…")}</span>
      <span className="muted">{hint}</span>
    </Link>
  )
}
