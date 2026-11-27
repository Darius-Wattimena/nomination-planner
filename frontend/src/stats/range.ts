import type {Column} from "./stats";

/**
 * The time range every statistics tab counts in, and the buckets it is split into.
 *
 * - parseRange(range, period?) -> key: the URL value ("7d" | "4w" | "3m" | "12m" | "ytd" | "2025" | "all" |
 *   "2025-01-01..2025-03-31"), also reading the old `period` param. Anything unknown becomes "12m".
 * - resolve(key, earliest?, now?) -> TimeRange: the instants [from, to), where rolling ranges end now and
 *   "all" starts at the earliest ranked date.
 * - previous(range) -> TimeRange | undefined: equally long and right before, shifted by the range's own length
 *   (so "ytd" compares to last year until the same day). None for "all". Its label reads "the 4 weeks before".
 * - autoUnit(range), unitsFor(range), unitFor(range, wanted?): which granularity fits, which are allowed
 *   (2 to 400 buckets), and the wanted one when allowed or else the automatic one.
 * - buckets(range, unit, now?) -> Bucket[]: in order, empty ones included, the unfinished one has `current`.
 * - bucketOf(date, unit), inRange(date, range), count(buckets, items, dateOf) -> Column[],
 *   group(buckets, items, dateOf) -> T[][].
 * - rolling(values, window?), dailyAverage(range, items, dateOf, window?): a trailing 7-day average.
 *
 * All in local time, stepping through the calendar so a change to summer time never moves a day. Weeks start on Monday.
 */

export type Unit = "day" | "week" | "month" | "year"
export const UNITS: Unit[] = ["day", "week", "month", "year"]

export const DEFAULT_RANGE = "12m"
export const PRESETS: {key: string, label: string}[] = [
  {key: "7d", label: "Last 7 days"},
  {key: "4w", label: "Last 4 weeks"},
  {key: "3m", label: "Last 3 months"},
  {key: "12m", label: "Last 12 months"},
  {key: "ytd", label: "This year"},
  {key: "all", label: "All time"}
]

export interface TimeRange {
  /** As in the URL */
  key: string
  /** Included */
  from: Date
  /** Not included */
  to: Date
  /** "Last 4 weeks", "2025", or for a previous range what it is compared to, like "the 4 weeks before" */
  label: string
  /** Ends now, so the last bucket is still growing */
  live: boolean
}

export interface Bucket {
  key: string
  /** In the tooltip and the table: "Mon 3 Nov", "Week of 3 Nov", "Nov 2026", "2026" */
  label: string
  /** Below the column */
  short: string
  /** Included, never before the range starts */
  start: Date
  /** Not included, never after the range ends */
  end: Date
  /** Not over yet */
  current: boolean
}

const DAY = 86_400_000
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]

const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate())
const addDays = (date: Date, days: number) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
const pad = (value: number) => String(value).padStart(2, "0")
const dayMonth = (date: Date) => `${date.getDate()} ${MONTHS[date.getMonth()]}`

/** Moves a date through the calendar keeping its time, the day kept within the month */
function shift(date: Date, {days = 0, months = 0}: {days?: number, months?: number}) {
  const target = new Date(date.getFullYear(), date.getMonth() + months, 1)
  const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  return new Date(target.getFullYear(), target.getMonth(), Math.min(date.getDate(), last) + days,
    date.getHours(), date.getMinutes(), date.getSeconds(), date.getMilliseconds())
}

/** "2025-03-01" as a local date */
export function dayString(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function parseDay(text: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!match) return undefined
  const [year, month, day] = match.slice(1).map(Number)
  const date = new Date(year, month - 1, day)
  return date.getMonth() === month - 1 && date.getDate() === day ? date : undefined
}

/** Calendar days from one day to another, also across summer time */
const daysBetween = (a: Date, b: Date) =>
  Math.round((Date.UTC(b.getFullYear(), b.getMonth(), b.getDate()) - Date.UTC(a.getFullYear(), a.getMonth(), a.getDate())) / DAY)

/** The key of a custom range, both days included */
export const customRange = (from: string, to: string) => `${from}..${to}`

function custom(key: string) {
  const [from, to] = key.split("..").map(parseDay)
  return from && to && from <= to ? {from, to} : undefined
}

/** The range in the URL, reading the old `period` too: "recent" is the last 12 months */
export function parseRange(range: string | null | undefined, period?: string | null): string {
  const value = range ?? (period === "recent" ? undefined : period)
  if (!value) return DEFAULT_RANGE
  if (PRESETS.some(it => it.key === value) || /^\d{4}$/.test(value) || (value.includes("..") && custom(value))) return value
  return DEFAULT_RANGE
}

export function resolve(key: string, earliest?: Date, now = new Date()): TimeRange {
  const today = startOfDay(now)
  const live = (from: Date, label: string): TimeRange => ({key, from, to: now, label, live: true})
  const preset = PRESETS.find(it => it.key === key)?.label ?? ""
  switch (key) {
    case "7d": return live(addDays(today, -6), preset)
    case "4w": return live(addDays(today, -27), preset)
    case "3m": return live(addDays(shift(today, {months: -3}), 1), preset)
    case "12m": return live(new Date(now.getFullYear(), now.getMonth() - 11, 1), preset)
    case "ytd": return live(new Date(now.getFullYear(), 0, 1), preset)
    case "all": return live(earliest && earliest < now ? startOfDay(earliest) : today, preset)
  }
  if (/^\d{4}$/.test(key)) {
    const from = new Date(Number(key), 0, 1)
    const end = new Date(Number(key) + 1, 0, 1)
    const isLive = from <= now && now < end
    return {key, from, to: isLive ? now : end < now ? end : from, label: key, live: isLive}
  }
  const days = custom(key)
  if (!days) return resolve(DEFAULT_RANGE, earliest, now)
  const to = addDays(days.to, 1)
  return {key, from: days.from, to, label: `${dayMonth(days.from)} ${days.from.getFullYear()} – ${dayMonth(days.to)} ${days.to.getFullYear()}`, live: days.from <= now && now < to}
}

/** The same length right before, to compare with. None for all time. */
export function previous(range: TimeRange): TimeRange | undefined {
  const moved = (by: {days?: number, months?: number}, label: string): TimeRange =>
    ({key: `${range.key}-before`, from: shift(range.from, by), to: shift(range.to, by), label, live: false})
  const sameDay = (year: number) => range.live ? `${year} until ${dayMonth(range.to)}` : String(year)
  switch (range.key) {
    case "all": return undefined
    case "7d": return moved({days: -7}, "the 7 days before")
    case "4w": return moved({days: -28}, "the 4 weeks before")
    case "3m": return moved({months: -3}, "the 3 months before")
    case "12m": return moved({months: -12}, "the 12 months before")
    case "ytd": return moved({months: -12}, sameDay(range.from.getFullYear() - 1))
  }
  if (/^\d{4}$/.test(range.key)) return moved({months: -12}, sameDay(range.from.getFullYear() - 1))
  const days = daysBetween(range.from, range.to)
  return moved({days: -days}, `the ${days} ${days === 1 ? "day" : "days"} before`)
}

export function inRange(date: Date | undefined, range: TimeRange) {
  return !!date && date >= range.from && date < range.to
}

/** The whole day, week, month or year a date is in */
export function bucketOf(date: Date, unit: Unit): {start: Date, end: Date} {
  const day = startOfDay(date)
  switch (unit) {
    case "day": return {start: day, end: addDays(day, 1)}
    case "week": {
      const monday = addDays(day, -((day.getDay() + 6) % 7))
      return {start: monday, end: addDays(monday, 7)}
    }
    case "month": return {start: new Date(day.getFullYear(), day.getMonth(), 1), end: new Date(day.getFullYear(), day.getMonth() + 1, 1)}
    case "year": return {start: new Date(day.getFullYear(), 0, 1), end: new Date(day.getFullYear() + 1, 0, 1)}
  }
}

/** The ISO week number and the year it belongs to */
export function isoWeek(date: Date) {
  const thursday = addDays(bucketOf(date, "week").start, 3)
  const year = thursday.getFullYear()
  return {year, week: Math.floor(daysBetween(new Date(year, 0, 1), thursday) / 7) + 1}
}

export function bucketCount(range: TimeRange, unit: Unit) {
  if (range.to <= range.from) return 0
  let count = 0
  for (let start = bucketOf(range.from, unit).start; start < range.to; start = bucketOf(start, unit).end) count++
  return count
}

/** Days for up to a month, weeks for up to half a year, months for up to 3 years, years after that */
export function autoUnit(range: TimeRange): Unit {
  const days = range.to > range.from ? daysBetween(range.from, new Date(range.to.getTime() - 1)) + 1 : 0
  return days <= 31 ? "day" : days <= 190 ? "week" : days <= 1096 ? "month" : "year"
}

/** The units that give between 2 and 400 buckets */
export function unitsFor(range: TimeRange): Unit[] {
  return UNITS.filter(unit => {
    const count = bucketCount(range, unit)
    return count >= 2 && count <= 400
  })
}

export function unitFor(range: TimeRange, wanted?: Unit | null): Unit {
  return wanted && unitsFor(range).includes(wanted) ? wanted : autoUnit(range)
}

export function buckets(range: TimeRange, unit: Unit, now = new Date()): Bucket[] {
  if (range.to <= range.from) return []
  const all: Bucket[] = []
  const years = range.from.getFullYear() !== new Date(range.to.getTime() - 1).getFullYear()
  for (let start = bucketOf(range.from, unit).start; start < range.to;) {
    const end = bucketOf(start, unit).end
    const year = years ? ` ${start.getFullYear()}` : ""
    const names: Record<Unit, [string, string]> = {
      day: [`${WEEKDAYS[start.getDay()]} ${dayMonth(start)}${year}`, dayMonth(start)],
      week: [`Week of ${dayMonth(start)}${year}`, dayMonth(start)],
      month: [`${MONTHS[start.getMonth()]} ${start.getFullYear()}`, years && start.getMonth() === 0 ? String(start.getFullYear()) : MONTHS[start.getMonth()]],
      year: [String(start.getFullYear()), String(start.getFullYear())]
    }
    all.push({
      key: `${unit}:${dayString(start)}`,
      label: names[unit][0],
      short: names[unit][1],
      start: start < range.from ? range.from : start,
      end: end > range.to ? range.to : end,
      current: start <= now && now < end
    })
    start = end
  }
  return all
}

/** The bucket a date is in, -1 when it is in none of them */
function indexOf(all: Bucket[], date: Date) {
  let low = 0, high = all.length - 1
  while (low <= high) {
    const middle = (low + high) >> 1
    if (date < all[middle].start) high = middle - 1
    else if (date >= all[middle].end) low = middle + 1
    else return middle
  }
  return -1
}

/** Puts every item in its bucket, leaving out those in none */
export function group<T>(all: Bucket[], items: T[], dateOf: (item: T) => Date | undefined): T[][] {
  const groups = all.map((): T[] => [])
  items.forEach(item => {
    const date = dateOf(item)
    const index = date ? indexOf(all, date) : -1
    if (index >= 0) groups[index].push(item)
  })
  return groups
}

/** Columns with the number of items in every bucket */
export function count<T>(all: Bucket[], items: T[], dateOf: (item: T) => Date | undefined): Column[] {
  return group(all, items, dateOf).map((own, index) => columnOf(all[index], own.length))
}

export function columnOf(bucket: Bucket, value: number): Column {
  return {key: bucket.key, short: bucket.short, label: bucket.label, value, current: bucket.current}
}

/** The average of each value and those before it, undefined until there are enough */
export function rolling(values: number[], window = 7): (number | undefined)[] {
  let sum = 0
  return values.map((value, index) => {
    sum += value - (index >= window ? values[index - window] : 0)
    return index >= window - 1 ? sum / window : undefined
  })
}

/** The trailing average per day of the range, also counting the days before it so it starts on the first day */
export function dailyAverage<T>(range: TimeRange, items: T[], dateOf: (item: T) => Date | undefined, window = 7, now = new Date()) {
  const longer = {...range, from: addDays(startOfDay(range.from), 1 - window)}
  const values = count(buckets(longer, "day", now), items, dateOf).map(it => it.value)
  return rolling(values, window).slice(window - 1)
}
