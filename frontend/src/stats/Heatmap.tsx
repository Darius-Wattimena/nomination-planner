import type {CSSProperties, KeyboardEvent} from "react";
import {useMemo, useState} from "react";
import {useRanked} from "../resources/ranked";
import {Table} from "./Columns";
import type {Day, Week} from "./insights";
import {heatmap, level, nominatedBy} from "./insights";
import {rankedDate} from "./stats";
import "./insights.css";

const WEEKDAYS = ["Mon", "", "Wed", "", "Fri", "", ""]

const long = (date: Date) => date.toLocaleDateString("en", {weekday: "short", day: "numeric", month: "long", year: "numeric"})
const short = (date: Date) => date.toLocaleDateString("en", {day: "numeric", month: "short", year: "numeric"})
const ranked = (count: number) => `${count === 0 ? "Nothing" : count} ranked`
const dayText = (day: Day) => `${ranked(day.count)} on ${long(day.date)}`
const weekText = (week: Week) => `${ranked(week.total)} in the week of ${short(week.start)}`

/** The week where every month starts, to put its name above */
function monthStarts(weeks: Week[]) {
  return weeks.flatMap((week, index) => {
    const first = week.days.find(it => it?.date.getDate() === 1) ?? (index === 0 ? week.days.find(Boolean) : undefined)
    return first ? [{index, label: first.date.toLocaleDateString("en", {month: "short"}), month: first.date.getMonth()}] : []
  })
}

/** Arrow keys move between the cells, so the whole calendar is one stop for the keyboard */
function useRoving(count: number, valid: (index: number) => boolean, step: {horizontal: number, vertical: number}) {
  const [focused, setFocused] = useState<number>()
  const last = (() => {
    for (let index = count - 1; index >= 0; index--) if (valid(index)) return index
    return 0
  })()
  const current = focused !== undefined && valid(focused) ? focused : last

  function onKeyDown(event: KeyboardEvent) {
    const moves: Record<string, number> = {ArrowRight: step.horizontal, ArrowLeft: -step.horizontal, ArrowDown: step.vertical, ArrowUp: -step.vertical}
    let next: number | undefined
    if (event.key in moves && moves[event.key] !== 0) {
      next = current + moves[event.key]
      while (next >= 0 && next < count && !valid(next)) next += Math.sign(moves[event.key])
    } else if (event.key === "Home") {
      next = Array.from({length: count}, (_, index) => index).find(valid)
    } else if (event.key === "End") {
      next = last
    }
    if (next === undefined || next < 0 || next >= count || !valid(next)) return
    event.preventDefault()
    setFocused(next)
    event.currentTarget.querySelector<HTMLElement>(`[data-index="${next}"]`)?.focus()
  }

  return {current, setFocused, onKeyDown}
}

/** When somebody nominated what got ranked, a day per cell, or a week per cell when there is little room */
export default function ActivityHeatmap({id, self}: {id: string, self: boolean}) {
  const {maps} = useRanked()
  const dates = useMemo(() => nominatedBy(maps ?? [], id).map(it => rankedDate(it)!), [maps, id])
  const map = useMemo(() => heatmap(dates), [dates])
  const days = map.weeks.flatMap(it => it.days)
  const [shown, setShown] = useState<string>()
  const grid = useRoving(days.length, index => !!days[index], {horizontal: 7, vertical: 1})
  const strip = useRoving(map.weeks.length, () => true, {horizontal: 1, vertical: 0})

  if (!maps || dates.length === 0) return null

  const starts = monthStarts(map.weeks)
  const busiest = days.reduce<Day | undefined>((best, it) => it && it.count > (best?.count ?? 0) ? it : best, undefined)
  const summary = `${map.total} ranked in the last 12 months${busiest && busiest.count > 1 ? `, the most on ${long(busiest.date)} with ${busiest.count}` : ""}`
  const style = {"--weeks": map.weeks.length} as CSSProperties

  return (
    <section className="map-group heatmap">
      <header><h2>{self ? "Your" : "Their"} last 12 months</h2><span className="muted">{map.total} ranked</span></header>
      <figure className="chart">
        <div className="heatmap-days" style={style}>
          <div className="heatmap-months" aria-hidden>
            {starts.map(it => <span key={it.index} style={{gridColumn: `${it.index + 1} / span 4`}}>{it.label}</span>)}
          </div>
          <div className="heatmap-weekdays" aria-hidden>{WEEKDAYS.map((it, index) => <span key={index}>{it}</span>)}</div>
          <div className="heatmap-grid" role="group" aria-label={`Ranked beatmaps per day. ${summary}. Use the arrow keys to go through the days.`}
               onKeyDown={grid.onKeyDown} onPointerLeave={() => setShown(undefined)}>
            {days.map((day, index) => day
              ? <span key={day.key} className="heatmap-cell" data-index={index} data-level={level(day.count, map.dayMax)} role="img" aria-label={dayText(day)} title={dayText(day)}
                      tabIndex={index === grid.current ? 0 : -1}
                      onFocus={() => { grid.setFocused(index); setShown(dayText(day)) }} onBlur={() => setShown(undefined)}
                      onPointerEnter={() => setShown(dayText(day))}/>
              : <span key={`empty-${index}`} className="heatmap-empty" aria-hidden/>)}
          </div>
        </div>

        <div className="heatmap-strip" style={style}>
          <div className="heatmap-months" aria-hidden>
            {starts.filter(it => it.month % 3 === 0).map(it => <span key={it.index} style={{gridColumn: `${it.index + 1} / span 8`}}>{it.label}</span>)}
          </div>
          <div className="heatmap-weeks" role="group" aria-label={`Ranked beatmaps per week. ${summary}. Use the arrow keys to go through the weeks.`}
               onKeyDown={strip.onKeyDown} onPointerLeave={() => setShown(undefined)}>
            {map.weeks.map((week, index) => (
              <span key={week.start.getTime()} className="heatmap-cell" data-index={index} data-level={level(week.total, map.weekMax)} role="img" aria-label={weekText(week)} title={weekText(week)}
                    tabIndex={index === strip.current ? 0 : -1}
                    onFocus={() => { strip.setFocused(index); setShown(weekText(week)) }} onBlur={() => setShown(undefined)}
                    onPointerEnter={() => setShown(weekText(week))} onPointerDown={() => setShown(weekText(week))}/>
            ))}
          </div>
        </div>

        <div className="heatmap-foot">
          <p className="muted heatmap-info" aria-live="polite">{shown ?? summary}</p>
          <p className="legend muted heatmap-legend" aria-hidden>
            Less{[0, 1, 2, 3, 4].map(it => <span key={it} className="heatmap-cell" data-level={it}/>)}More
          </p>
        </div>
        <Table head={["Day", "ranked"]} rows={days.filter((it): it is Day => !!it && it.count > 0).reverse().map(it => [long(it.date), it.count])}/>
      </figure>
    </section>
  )
}
