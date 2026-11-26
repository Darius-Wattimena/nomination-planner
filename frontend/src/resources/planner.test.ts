// @vitest-environment jsdom
import {describe, expect, it, vi} from "vitest";

const request = vi.fn()
vi.mock("./client", () => ({request, useQuery: vi.fn()}))

const {reloadPlanner} = await import("./planner")

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
