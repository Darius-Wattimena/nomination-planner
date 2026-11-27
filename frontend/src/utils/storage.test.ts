import {beforeEach, describe, expect, it, vi} from "vitest";
import {forget, readStored, readText, store, storeFirst, storeText, STORAGE_VERSION} from "./storage";

let storage: Map<string, string>
let room: number

beforeEach(() => {
  storage = new Map()
  room = Infinity
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => {
      const used = Array.from(storage.entries()).filter(([it]) => it !== key).reduce((sum, [, it]) => sum + it.length, 0)
      if (used + value.length > room) throw new Error("QuotaExceededError")
      storage.set(key, value)
    },
    removeItem: (key: string) => storage.delete(key)
  })
})

describe("storage", () => {
  it("reads what was stored", () => {
    expect(store("views", [{name: "Mine"}])).toBe(true)
    expect(readStored("views")).toEqual([{name: "Mine"}])
    expect(readStored("unknown")).toBeUndefined()
    storage.set("theme", "light")
    expect(readText("theme")).toBe("light")
    expect(readStored("theme")).toBeUndefined()
  })

  it("keeps what people chose before there were versions, but not the caches", () => {
    storage.set("views", JSON.stringify([{name: "Mine"}]))
    expect(readStored("views")).toEqual([{name: "Mine"}])
    storage.set("planner", JSON.stringify({token: "t", users: [], beatmaps: []}))
    expect(readStored("planner")).toBeUndefined()
  })

  it("reads what another version stored as nothing", () => {
    storage.set("views", JSON.stringify({version: STORAGE_VERSION - 1, value: [{name: "Mine"}]}))
    expect(readStored("views")).toBeUndefined()
    storage.set("views", JSON.stringify({version: STORAGE_VERSION, value: [{name: "Mine"}]}))
    expect(readStored("views")).toEqual([{name: "Mine"}])
  })

  it("keeps plain text preferences as they were", () => {
    expect(storeText("volume", "0.5")).toBe(true)
    expect(storage.get("volume")).toBe("0.5")
    expect(readText("volume")).toBe("0.5")
  })

  it("never throws when storage is blocked", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => {
        throw new Error("SecurityError")
      },
      setItem: () => {
        throw new Error("SecurityError")
      },
      removeItem: () => {
        throw new Error("SecurityError")
      }
    })

    expect(readStored("views")).toBeUndefined()
    expect(readText("theme")).toBeUndefined()
    expect(store("views", [])).toBe(false)
    expect(() => forget("views")).not.toThrow()
  })

  it("drops the caches to make room for what matters", () => {
    store("planner", "x".repeat(50))
    store("ranked", "x".repeat(40))
    room = 100

    expect(store("userContext", "y".repeat(20))).toBe(false)
    expect(storeFirst("userContext", "y".repeat(20))).toBe(true)
    expect(storage.has("planner")).toBe(false)
    expect(storage.has("ranked")).toBe(false)
  })
})
