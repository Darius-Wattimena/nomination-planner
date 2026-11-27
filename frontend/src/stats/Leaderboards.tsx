import type {ReactNode} from "react";
import {useMemo, useState} from "react";
import {Link} from "react-router";
import {FaCalendarCheck, FaStopwatch} from "react-icons/fa6";
import {GAMEMODE_NAMES} from "../Constants";
import ModeIcon from "../ui/ModeIcon";
import {Avatar} from "../ui/Person";
import {useSession} from "../utils/session";
import {BoardIcon} from "./Badges";
import type {Board, Entry, Move} from "./boards";
import {boards as boardsOf, boardsPerMode, movement, scores} from "./boards";
import {useScope} from "./useScope";
import {displayName} from "../utils/users";
import "./boardsSky.css";

const SHOWN = 10
const PER_MODE = 3

/** Icons of the boards that have no badge */
const ICONS: Record<string, ReactNode> = {speed: <FaStopwatch/>, steady: <FaCalendarCheck/>}

function Icon({id}: {id: string}) {
  return ICONS[id] ? <span className="board-icon" aria-hidden>{ICONS[id]}</span> : <BoardIcon id={id}/>
}

export default function Leaderboards({boards}: {boards: Board[]}) {
  const self = useSession()?.user?.osuId
  const {mode, before, previousScope, scope, firsts} = useScope()
  const [picked, setPicked] = useState(boards[0].id)
  const [all, setAll] = useState(false)
  const board = boards.find(it => it.id === picked) ?? boards[0]

  // The same boards in the range before, to see who moved
  const earlier = useMemo(() => previousScope && boardsOf(scores(previousScope, firsts, mode)), [previousScope, firsts, mode])
  const moves = useMemo(() => {
    const was = earlier?.find(it => it.id === board.id)
    return was ? movement(board.entries, was.entries) : undefined
  }, [earlier, board])
  const modes = useMemo(() => mode ? [] : boardsPerMode(scope, firsts), [mode, scope, firsts])

  const shown = all ? board.entries : board.entries.slice(0, SHOWN)
  const podium = shown.slice(0, 3)
  const rest = shown.slice(3)
  const own = board.entries.findIndex(it => it.user.osuId === self)
  // The one right above you, as something to aim for
  const better = (a: number, b: number) => board.lowerIsBetter ? a < b : a > b
  const ahead = own > 0 ? board.entries.slice(0, own).reverse().find(it => better(it.value, board.entries[own].value)) : undefined
  const row = (it: Entry, large?: boolean) =>
    <Row key={it.user.osuId} entry={it} board={board} self={self} large={large} move={moves?.get(it.user.osuId)}/>

  return (
    <div className="leaderboards">
      <div className="board-tabs" role="group" aria-label="Leaderboard">
        {boards.map(it => (
          <button key={it.id} className="board-tab" aria-pressed={it.id === board.id} onClick={() => {
            setPicked(it.id)
            setAll(false)
          }}>
            <Icon id={it.id}/><span><strong>{it.title}</strong><span>{it.hint}</span></span>
          </button>
        ))}
      </div>
      <div className="board-list">
        {board.entries.length === 0 && <p className="muted">Nobody yet. This one is up for grabs.</p>}
        {moves && board.entries.length > 0 && <p className="muted board-compared">Places compared with {before?.label}</p>}
        <ol className="podium">{podium.map(it => row(it, true))}</ol>
        <ol className="board-rest">{rest.map(it => row(it))}</ol>
        {own >= shown.length &&
          <ol className="board-rest board-own" aria-label="Your place">{row(board.entries[own])}</ol>
        }
        {board.entries.length > SHOWN &&
          <button className="link board-more" aria-expanded={all} onClick={() => setAll(!all)}>
            {all ? `Show the top ${SHOWN}` : `Show all ${board.entries.length}`}
          </button>
        }
        {ahead && <p className="muted">{board.lowerIsBetter
          ? `${board.entries[own].value - ahead.value} ${board.entries[own].value - ahead.value === 1 ? "day" : "days"} faster to catch up with ${displayName(ahead.user)}.`
          : `${ahead.value - board.entries[own].value} more to catch up with ${displayName(ahead.user)}.`}</p>}

        {modes.length > 1 &&
          <section className="board-modes" aria-label={`Top ${PER_MODE} per mode`}>
            <h3>Top {PER_MODE} per mode</h3>
            <div className="board-modes-list">
              {modes.map(({mode: own, boards: list}) => {
                const entries = list.find(it => it.id === board.id)?.entries.slice(0, PER_MODE) ?? []
                return (
                  <div key={own} className="board-mode">
                    <h4><ModeIcon mode={own}/>{GAMEMODE_NAMES[own]}</h4>
                    {entries.length === 0 && <p className="muted">Nobody yet</p>}
                    <ol>
                      {entries.map(it => (
                        <li key={it.user.osuId} data-self={it.user.osuId === self || undefined}>
                          <span className="board-mode-place">{it.place}</span>
                          <Link to={`/team?user=${it.user.osuId}`}>{displayName(it.user)}</Link>
                          <span className="board-value">{board.unit(it.value)}</span>
                        </li>
                      ))}
                    </ol>
                  </div>
                )
              })}
            </div>
          </section>
        }
      </div>
    </div>
  )
}

/** "▲ 3", "▼ 2", "new" or "–", in words for a screen reader */
function Movement({move}: {move: Move}) {
  const [shown, said] = move.kind === "up" ? [`▲${move.by}`, `up ${move.by}`]
    : move.kind === "down" ? [`▼${move.by}`, `down ${move.by}`]
      : move.kind === "new" ? ["new", "new on this board"] : ["–", "same place"]
  return <span className="board-move" data-move={move.kind} title={said}><span aria-hidden>{shown}</span><span className="sr-text">, {said}</span></span>
}

function Row({entry, board, self, large, move}: {entry: Entry, board: Board, self?: string, large?: boolean, move?: Move}) {
  return (
    <li>
      <Link className="board-row board-row-moves" to={`/team?user=${entry.user.osuId}`} data-self={entry.user.osuId === self || undefined}
            aria-current={entry.user.osuId === self ? "true" : undefined}>
        <span className="place" data-place={large ? entry.place : undefined}>{entry.place}</span>
        <Avatar user={entry.user} size={large ? "large" : "small"}/>
        <strong>{displayName(entry.user)}{entry.user.osuId === self && <span className="muted"> (you)</span>}</strong>
        <span className="board-move-cell">{move && <Movement move={move}/>}</span>
        <span className="board-value">{board.unit(entry.value)}</span>
      </Link>
    </li>
  )
}
