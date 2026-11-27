import {describe, expect, it} from "vitest";
import {PASS_DAYS, stillValid} from "./choices";

const DAY = 86_400_000

describe("stillValid", () => {
  const now = Date.UTC(2026, 5, 15)

  it("forgets passes after 30 days, but keeps what you were interested in", () => {
    expect(PASS_DAYS).toBe(30)
    const stored = {
      liked: {choice: "like", at: now - 400 * DAY},
      recent: {choice: "pass", at: now - 29 * DAY},
      expired: {choice: "pass", at: now - 30 * DAY}
    }
    expect(Object.keys(stillValid(stored, now))).toEqual(["liked", "recent"])
  })

  it("leaves out what isn't a choice", () => {
    expect(stillValid({a: null, b: {choice: "maybe", at: now}, c: {choice: "pass"}}, now)).toEqual({})
    expect(stillValid({old: {choice: "like"}}, now)).toEqual({old: {choice: "like"}})
    expect(stillValid("nonsense", now)).toEqual({})
    expect(stillValid(undefined, now)).toEqual({})
  })
})
