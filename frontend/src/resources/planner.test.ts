// @vitest-environment jsdom
import {describe, expect, it, vi} from "vitest";
import {Gamemode} from "../models/Types";

const request = vi.fn()
const find = vi.fn()
vi.mock("./client", () => ({request, find, statusOf: (error: {response?: {status: number}}) => error.response?.status, useQuery: vi.fn()}))

const {ConflictError, reloadPlanner, setNominator} = await import("./planner")

describe("reloadPlanner", () => {
  it("shares the reload that is running instead of starting another", async () => {
    let answer: (value: unknown) => void = () => undefined
    request.mockReturnValueOnce(new Promise(resolve => answer = resolve))

    const first = reloadPlanner()
    const second = reloadPlanner()
    answer({token: "t", isComplete: true, users: [], beatmaps: [], removed: []})
    await Promise.all([first, second])

    expect(second).toBe(first)
    expect(request).toHaveBeenCalledTimes(1)
  })

  it("starts a new reload once the last one is done", async () => {
    request.mockClear()
    request.mockResolvedValue({token: "t", isComplete: true, users: [], beatmaps: [], removed: []})

    await reloadPlanner()
    await reloadPlanner()

    expect(request).toHaveBeenCalledTimes(2)
  })
})

describe("setNominator", () => {
  it("tells when somebody else changed the nominators first", async () => {
    request.mockRejectedValueOnce({response: {status: 409}})
    find.mockResolvedValueOnce(undefined)

    await expect(setNominator("1", Gamemode.Osu, "0", "5")).rejects.toBeInstanceOf(ConflictError)
    expect(find).toHaveBeenCalledTimes(1)
  })

  it("passes on other failures", async () => {
    const failure = {response: {status: 500}}
    request.mockRejectedValueOnce(failure)

    await expect(setNominator("1", Gamemode.Osu, "0", "5")).rejects.toBe(failure)
  })
})
