import {describe, expect, it} from "vitest";
import {beatmap} from "../test/builders";
import {group, timeline} from "./timeline";

const now = new Date(2026, 5, 15)

describe("timeline", () => {
  it("has the months of a year until now", () => {
    const line = timeline([], {year: 2026, lastYear: false}, now)
    expect(line.unit).toBe("month")
    expect(line.columns.map(it => it.short)).toEqual(["Jan", "Feb", "Mar", "Apr", "May", "Jun"])
    expect(line.indexOf(new Date(2026, 2, 3))).toBe(2)
    expect(line.indexOf(new Date(2025, 2, 3))).toBe(-1)
  })

  it("has the last 12 months", () => {
    const line = timeline([], {lastYear: true}, now)
    expect(line.columns).toHaveLength(12)
    expect(line.indexOf(new Date(2025, 6, 1))).toBe(0)
  })

  it("has years when there are many months", () => {
    const maps = [beatmap({dateRanked: "2020-03-01T12:00:00Z"})]
    const line = timeline(maps, {lastYear: false}, now)
    expect(line.unit).toBe("year")
    expect(line.columns.every(it => it.value === 0)).toBe(true)
    expect(line.indexOf(new Date(2021, 4, 1))).toBe(1)
  })

  it("groups items by their column", () => {
    const line = timeline([], {year: 2025, lastYear: false}, now)
    const dates = [new Date(2025, 0, 2), new Date(2025, 0, 20), new Date(2024, 0, 1), undefined]
    const groups = group(line, dates, it => it)
    expect(groups).toHaveLength(12)
    expect(groups[0]).toHaveLength(2)
    expect(groups.flat()).toHaveLength(2)
  })
})
