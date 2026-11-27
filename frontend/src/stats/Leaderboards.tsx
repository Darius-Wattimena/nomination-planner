import {useState} from "react";
import {Link} from "react-router";
import {Avatar} from "../ui/Person";
import {useSession} from "../utils/session";
import {BoardIcon} from "./Badges";
import type {Board, Entry} from "./boards";
import {displayName} from "../utils/users";

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
        {ahead && <p className="muted">{ahead.value - board.entries[own].value} more to catch up with {displayName(ahead.user)}.</p>}
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
        <strong>{displayName(entry.user)}</strong>
        <span className="board-value">{board.unit(entry.value)}</span>
      </Link>
    </li>
  )
}
