// @vitest-environment jsdom
import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(2026, 5, 1, 23, 0))
  vi.resetModules()
})
afterEach(() => {
  vi.useRealTimers()
})

describe("today", () => {
  it("moves on only once the day changed", async () => {
    const {checkToday, getToday, subscribeToday} = await import("./today")
    const listener = vi.fn()
    const stop = subscribeToday(listener)
    const start = getToday()

    checkToday(new Date(2026, 5, 1, 23, 30).getTime())
    expect(getToday()).toBe(start)
    expect(listener).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(61 * 60_000)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(new Date(getToday()).getDate()).toBe(2)
    stop()
  })

  it("checks again when the page becomes visible, as hidden pages can be late with timers", async () => {
    const {getToday, subscribeToday} = await import("./today")
    const listener = vi.fn()
    const stop = subscribeToday(listener)
    vi.setSystemTime(new Date(2026, 5, 3, 9, 0))

    document.dispatchEvent(new Event("visibilitychange"))

    expect(listener).toHaveBeenCalledTimes(1)
    expect(new Date(getToday()).getDate()).toBe(3)
    stop()
  })
})
