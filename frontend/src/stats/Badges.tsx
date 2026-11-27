import {FaDoorOpen, FaDumbbell, FaPeopleGroup, FaSeedling, FaShuffle} from "react-icons/fa6";
import type {Badge} from "./boards";

const ICONS = {scout: <FaSeedling/>, variety: <FaDoorOpen/>, partners: <FaPeopleGroup/>, hybrids: <FaShuffle/>, volume: <FaDumbbell/>}

export function BoardIcon({id}: {id: string}) {
  return <span className="board-icon" aria-hidden>{ICONS[id as keyof typeof ICONS]}</span>
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
