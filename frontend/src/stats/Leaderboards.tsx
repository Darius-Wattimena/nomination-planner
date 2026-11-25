import {useState} from "react";
import {Link} from "react-router-dom";
import {FaDoorOpen, FaDumbbell, FaPeopleGroup, FaSeedling, FaShuffle} from "react-icons/fa6";
import {Avatar} from "../ui/Person";
import {useSession} from "../utils/session";
import {Badge, Board, Entry} from "./boards";

const ICONS = {scout: <FaSeedling/>, variety: <FaDoorOpen/>, partners: <FaPeopleGroup/>, hybrids: <FaShuffle/>, volume: <FaDumbbell/>}

export function BoardIcon({id}: {id: string}) {
  return <span className="board-icon" aria-hidden>{ICONS[id as keyof typeof ICONS]}</span>
}

const SHOWN = 8

export default function Leaderboards({boards}: {boards: Board[]}) {
  const self = useSession()?.user?.osuId
  const [picked, setPicked] = useState(boards[0].id)
  const board = boards.find(it => it.id === picked) ?? boards[0]
  const podium = board.entries.slice(0, 3)
  const rest = board.entries.slice(3, SHOWN)
  const own = board.entries.findIndex(it => it.user.osuId === self)
  // The one right above you, as something to aim for
  const ahead = own > 0 ? board.entries.slice(0, own).reverse().find(it => it.value > board.entries[own].value) : undefined

  return (
    <div className="leaderboards">
      <div className="board-tabs" role="group" aria-label="Leaderboard">
        {boards.map(it => (
          <button key={it.id} className="board-tab" aria-pressed={it.id === board.id} onClick={() => setPicked(it.id)}>
            <BoardIcon id={it.id}/><span><strong>{it.title}</strong><span>{it.hint}</span></span>
          </button>
        ))}
      </div>
      <div className="board-list">
        {board.entries.length === 0 && <p className="muted">Nobody yet. This one is up for grabs.</p>}
        <ol className="podium">{podium.map(it => <Row key={it.user.osuId} entry={it} board={board} self={self} large/>)}</ol>
        <ol className="board-rest">
          {rest.map(it => <Row key={it.user.osuId} entry={it} board={board} self={self}/>)}
          {own >= SHOWN && <Row entry={board.entries[own]} board={board} self={self}/>}
        </ol>
        {ahead && <p className="muted">{ahead.value - board.entries[own].value} more to catch up with {ahead.user.username}.</p>}
      </div>
    </div>
  )
}

function Row({entry, board, self, large}: {entry: Entry, board: Board, self?: string, large?: boolean}) {
  return (
    <li>
      <Link className="board-row" to={`/team?user=${entry.user.osuId}`} data-self={entry.user.osuId === self || undefined}>
        <span className="place" data-place={large ? entry.place : undefined}>{entry.place}</span>
        <Avatar user={entry.user} size={large ? "large" : "small"}/>
        <strong>{entry.user.username}</strong>
        <span className="board-value">{board.unit(entry.value)}</span>
      </Link>
    </li>
  )
}

export function Badges({badges}: {badges: Badge[]}) {
  return (
    <ul className="badges">
      {worthShowing(badges).map(it => {
        const from = it.level > 0 ? it.steps[it.level - 1] : 0
        const progress = it.next ? (it.value - from) / (it.next - from) : 1
        const text = it.next ? `${it.value} of ${it.next} ${it.what}` : `${it.value} ${it.what}`
        return (
          <li key={it.id} className="badge" data-level={it.level}>
            <span className="badge-ring">
              <svg viewBox="0 0 64 64" aria-hidden>
                <circle className="badge-track" cx="32" cy="32" r="28"/>
                {progress > 0 && <circle className="badge-progress" cx="32" cy="32" r="28" pathLength={100} strokeDasharray={`${progress * 100} 100`}/>}
              </svg>
              <BoardIcon id={it.id}/>
            </span>
            <span><strong>{it.title}{it.level > 0 && ` ${["I", "II", "III", "IV"][it.level - 1]}`}</strong><span className="muted">{text}</span></span>
          </li>
        )
      })}
    </ul>
  )
}

/** The earned ones and whatever is closest, instead of a list of everything that is missing */
function worthShowing(badges: Badge[]) {
  const earned = badges.filter(it => it.level > 0)
  const next = badges.filter(it => it.level === 0).sort((a, b) => b.value / b.steps[0] - a.value / a.steps[0])[0]
  return next ? [...earned, next] : earned
}
