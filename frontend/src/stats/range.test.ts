import {describe, expect, it} from "vitest";
import type {TimeRange} from "./range";
import {autoUnit, bucketOf, buckets, count, dailyAverage, dayString, group, inRange, isoWeek, parseRange, previous, resolve, rolling, unitFor, unitsFor} from "./range";

// A Friday afternoon
const now = new Date(2026, 10, 27, 15, 30)
const at = (key: string, earliest?: Date) => resolve(key, earliest, now)
const days = (range: TimeRange | undefined) => range ? [dayString(range.from), dayString(range.to)] : []

describe("parseRange", () => {
  it("keeps what it knows", () => {
    ["7d", "4w", "3m", "12m", "ytd", "all", "2025", "2025-03-01..2025-03-31", "2025-03-01..2025-03-01"].forEach(it => expect(parseRange(it)).toBe(it))
  })

  it("falls back to the last 12 months", () => {
    [null, undefined, "", "5y", "2025-02-30..2025-03-01", "2025-03-31..2025-03-01", "2025-3-1..2025-3-2"].forEach(it => expect(parseRange(it)).toBe("12m"))
  })

  it("reads the old period", () => {
    expect(parseRange(null, "recent")).toBe("12m")
    expect(parseRange(null, "all")).toBe("all")
    expect(parseRange(null, "2024")).toBe("2024")
    expect(parseRange(null, "nonsense")).toBe("12m")
    expect(parseRange("4w", "2024")).toBe("4w")
  })
})

describe("resolve", () => {
  it("rolls until now", () => {
    expect(days(at("7d"))).toEqual(["2026-11-21", "2026-11-27"])
    expect(at("7d").to).toBe(now)
    expect(at("7d").live).toBe(true)
    expect(days(at("4w"))[0]).toBe("2026-10-31")
    expect(days(at("3m"))[0]).toBe("2026-08-28")
    // The current month and the 11 before, like the statistics always had
    expect(days(at("12m"))[0]).toBe("2025-12-01")
    expect(days(at("ytd"))[0]).toBe("2026-01-01")
  })

  it("has whole years, the current one until now", () => {
    expect(at("2025")).toMatchObject({from: new Date(2025, 0, 1), to: new Date(2026, 0, 1), live: false, label: "2025"})
    expect(at("2026")).toMatchObject({from: new Date(2026, 0, 1), to: now, live: true})
  })

  it("starts all time at the first ranked beatmap", () => {
    expect(at("all", new Date(2021, 3, 5, 12)).from).toEqual(new Date(2021, 3, 5))
    expect(at("all").from).toEqual(new Date(2026, 10, 27))
  })

  it("includes both days of a custom range", () => {
    expect(at("2025-03-01..2025-03-31")).toMatchObject({from: new Date(2025, 2, 1), to: new Date(2025, 3, 1), live: false, label: "1 Mar 2025 – 31 Mar 2025"})
  })

  it("knows what is in it", () => {
    expect(inRange(new Date(2025, 0, 1), at("2025"))).toBe(true)
    expect(inRange(new Date(2026, 0, 1), at("2025"))).toBe(false)
    expect(inRange(undefined, at("2025"))).toBe(false)
  })
})

describe("previous", () => {
  it("is as long and right before", () => {
    const before = previous(at("4w"))!
    expect(days(before)).toEqual(["2026-10-03", "2026-10-30"])
    expect(before.to.getHours()).toBe(15)
    expect(before.label).toBe("the 4 weeks before")
    expect(days(previous(at("7d")))).toEqual(["2026-11-14", "2026-11-20"])
    expect(days(previous(at("12m")))).toEqual(["2024-12-01", "2025-11-27"])
    expect(days(previous(at("2025-03-01..2025-03-31")))).toEqual(["2025-01-29", "2025-03-01"])
    expect(previous(at("2025-03-01..2025-03-31"))!.label).toBe("the 31 days before")
  })

  it("compares a year to the one before, an unfinished one until the same day", () => {
    expect(previous(at("2025"))).toMatchObject({from: new Date(2024, 0, 1), to: new Date(2025, 0, 1), label: "2024"})
    expect(previous(at("ytd"))).toMatchObject({from: new Date(2025, 0, 1), to: new Date(2025, 10, 27, 15, 30), label: "2025 until 27 Nov"})
    expect(previous(at("2026"))!.label).toBe("2025 until 27 Nov")
  })

  it("has nothing before all time", () => {
    expect(previous(at("all", new Date(2020, 0, 1)))).toBeUndefined()
  })
})

describe("granularity", () => {
  it("picks one that fits the length", () => {
    expect(autoUnit(at("7d"))).toBe("day")
    expect(autoUnit(at("4w"))).toBe("day")
    expect(autoUnit(at("3m"))).toBe("week")
    expect(autoUnit(at("12m"))).toBe("month")
    expect(autoUnit(at("all", new Date(2024, 0, 1)))).toBe("month")
    expect(autoUnit(at("all", new Date(2020, 0, 1)))).toBe("year")
  })

  it("switches at 31 days, 190 days and 3 years", () => {
    expect(autoUnit(at("2025-01-01..2025-01-31"))).toBe("day")
    expect(autoUnit(at("2025-01-01..2025-02-01"))).toBe("week")
    expect(autoUnit(at("2025-01-01..2025-07-09"))).toBe("week")
    expect(autoUnit(at("2025-01-01..2025-07-10"))).toBe("month")
    expect(autoUnit(at("2022-01-01..2024-12-31"))).toBe("month")
    expect(autoUnit(at("2022-01-01..2025-01-02"))).toBe("year")
  })

  it("only allows between 2 and 400 buckets, and otherwise picks itself", () => {
    expect(unitsFor(at("7d"))).toEqual(["day", "week"])
    expect(unitsFor(at("all", new Date(2020, 0, 1)))).toEqual(["week", "month", "year"])
    expect(unitFor(at("7d"), "week")).toBe("week")
    expect(unitFor(at("7d"), "year")).toBe("day")
    expect(unitFor(at("7d"))).toBe("day")
  })
})

describe("weeks", () => {
  it("start on Monday", () => {
    expect(bucketOf(new Date(2026, 10, 29, 23), "week")).toEqual({start: new Date(2026, 10, 23), end: new Date(2026, 10, 30)})
    expect(bucketOf(new Date(2026, 10, 30), "week").start).toEqual(new Date(2026, 10, 30))
  })

  it("are numbered like ISO 8601", () => {
    expect(isoWeek(new Date(2026, 0, 1))).toEqual({year: 2026, week: 1})
    expect(isoWeek(new Date(2027, 0, 1))).toEqual({year: 2026, week: 53})
    expect(isoWeek(new Date(2024, 11, 30))).toEqual({year: 2025, week: 1})
    expect(isoWeek(new Date(2026, 10, 27))).toEqual({year: 2026, week: 48})
  })
})

describe("buckets", () => {
  it("has every day with its label, the last one unfinished", () => {
    const list = buckets(at("4w"), "day", now)
    expect(list).toHaveLength(28)
    expect(list[0]).toMatchObject({label: "Sat 31 Oct", short: "31 Oct", start: new Date(2026, 9, 31), end: new Date(2026, 10, 1), current: false})
    expect(list[27]).toMatchObject({label: "Fri 27 Nov", end: now, current: true})
    expect(list.filter(it => it.current)).toHaveLength(1)
  })

  it("names weeks, months and years, and keeps them within the range", () => {
    const weeks = buckets(at("3m"), "week", now)
    expect(weeks[0]).toMatchObject({label: "Week of 24 Aug", start: new Date(2026, 7, 28)})
    expect(weeks[weeks.length - 1]).toMatchObject({label: "Week of 23 Nov", current: true})
    const months = buckets(at("12m"), "month", now)
    expect(months.map(it => it.short)).toEqual(["Dec", "2026", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov"])
    expect(months[11].label).toBe("Nov 2026")
    expect(buckets(at("2025"), "month", now).map(it => it.short)[0]).toBe("Jan")
    expect(buckets(at("2025"), "month", now).some(it => it.current)).toBe(false)
    expect(buckets(at("all", new Date(2023, 5, 1)), "year", now).map(it => it.label)).toEqual(["2023", "2024", "2025", "2026"])
    expect(buckets(at("2025-12-29..2026-01-04"), "day", now)[0].label).toBe("Mon 29 Dec 2025")
  })

  it("keeps days whole across summer time", () => {
    // Summer time starts and ends in Europe and the US within these
    const list = [...buckets(at("2026-03-01..2026-04-05"), "day", now), ...buckets(at("2026-10-20..2026-11-05"), "day", now)]
    list.forEach(it => {
      expect(it.start.getHours()).toBe(0)
      expect(it.end.getHours()).toBe(0)
    })
    expect(list.slice(0, 36).map(it => it.start.getDate())).toEqual([...Array.from({length: 31}, (_, index) => index + 1), 1, 2, 3, 4, 5])
    expect(list.map(it => it.label)).toContain("Sun 29 Mar")
    expect(list.map(it => it.label)).toContain("Sun 25 Oct")
  })

  it("counts and groups, empty buckets included", () => {
    const list = buckets(at("2025"), "month", now)
    const dates = [new Date(2025, 0, 2), new Date(2025, 0, 31, 23, 59), new Date(2025, 11, 31, 23), new Date(2024, 11, 31, 23), new Date(2026, 0, 1), undefined]
    const columns = count(list, dates, it => it)
    expect(columns).toHaveLength(12)
    expect(columns.map(it => it.value)).toEqual([2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1])
    expect(columns[0]).toMatchObject({key: "month:2025-01-01", label: "Jan 2025", short: "Jan", current: false})
    expect(group(list, dates, it => it)[0]).toEqual(dates.slice(0, 2))
  })

  it("leaves out what is in the first week but before the range", () => {
    const list = buckets(at("3m"), "week", now)
    expect(count(list, [new Date(2026, 7, 25), new Date(2026, 7, 29)], it => it)[0].value).toBe(1)
  })

  it("has none for an empty range", () => {
    expect(buckets(at("2030"), "month", now)).toEqual([])
  })
})

describe("rolling average", () => {
  it("averages the last values once there are enough", () => {
    expect(rolling([1, 2, 3, 4, 5], 3)).toEqual([undefined, undefined, 2, 3, 4])
    expect(rolling([7, 0, 0, 0, 0, 0, 0, 0])).toEqual([undefined, undefined, undefined, undefined, undefined, undefined, 1, 0])
  })

  it("counts the days before the range so it starts on its first day", () => {
    const dates = [new Date(2026, 10, 15, 12), new Date(2026, 10, 21, 9), new Date(2026, 10, 27, 9)]
    const line = dailyAverage(at("7d"), dates, it => it, 7, now)
    expect(line).toHaveLength(7)
    expect(line[0]).toBeCloseTo(2 / 7)
    expect(line[1]).toBeCloseTo(1 / 7)
    expect(line[6]).toBeCloseTo(2 / 7)
  })
})
