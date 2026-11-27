// @vitest-environment jsdom
import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {beatmap, bn, NOBODY} from "../test/builders";

const request = vi.fn()
const find = vi.fn()
// Reads the store without rendering
vi.mock("react", async original => ({...await original<object>(), useSyncExternalStore: (_: unknown, get: () => unknown) => get()}))
vi.mock("./client", () => ({request, find, statusOf: (error: {response?: {status: number}}) => error.response?.status, useQuery: vi.fn()}))

const {changeNominator, ConflictError, reloadPlanner, setNominator, UNDO_TIME, usePlanner} = await import("./planner")

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

describe("updates", () => {
  const map = (id: string, title = `Title ${id}`) => ({
    id, artist: "Artist", title, mapper: "10", status: BeatmapStatus.Pending,
    modes: [{mode: Gamemode.Osu, by: [{user: "1"}]}], added: "2026-01-01T00:00:00Z", updated: "2026-01-02T00:00:00Z"
  })

  it("keeps the beatmaps that didn't change when the update carries users that stayed the same", async () => {
    request.mockReset()
    request.mockResolvedValueOnce({token: "t1", isComplete: true, users: [bn("1")], beatmaps: [map("1"), map("2")], removed: []})
    await reloadPlanner()
    const before = usePlanner().maps!

    // The backend sends the users of every beatmap that changed
    request.mockResolvedValueOnce({token: "t2", isComplete: false, users: [bn("1")], beatmaps: [map("2", "New")], removed: []})
    await reloadPlanner()
    const after = usePlanner().maps!

    expect(after.find(it => it.osuId === "1")).toBe(before.find(it => it.osuId === "1"))
    expect(after.find(it => it.osuId === "2")?.title).toBe("New")
  })

  it("makes the beatmaps again when a user changed", async () => {
    const before = usePlanner().maps!
    request.mockResolvedValueOnce({token: "t3", isComplete: false, users: [{...bn("1"), username: "Renamed"}], beatmaps: [], removed: []})
    await reloadPlanner()
    const after = usePlanner().maps!

    expect(after.find(it => it.osuId === "1")).not.toBe(before.find(it => it.osuId === "1"))
    expect(after.find(it => it.osuId === "1")?.gamemodes[0].nominators[0].nominator.username).toBe("Renamed")
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

describe("changeNominator", () => {
  const sent = () => request.mock.calls.map(([config]) => `${config.params.old}>${config.params.new}`)

  beforeEach(() => {
    vi.useFakeTimers()
    request.mockReset()
    request.mockResolvedValue(beatmap())
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it("sends nothing when it is undone in time", async () => {
    const undo = changeNominator("1", Gamemode.Osu, "0", bn("5"), vi.fn())
    undo()
    await vi.advanceTimersByTimeAsync(UNDO_TIME)

    expect(request).not.toHaveBeenCalled()
  })

  it("takes back what was sent", async () => {
    const undo = changeNominator("1", Gamemode.Osu, "0", bn("5"), vi.fn())
    await vi.advanceTimersByTimeAsync(UNDO_TIME)
    undo()
    await vi.runAllTimersAsync()

    expect(sent()).toEqual(["0>5", "5>0"])
  })

  it("doesn't take back what the backend refused", async () => {
    const onError = vi.fn()
    request.mockRejectedValueOnce({response: {status: 500}})
    const undo = changeNominator("1", Gamemode.Osu, "0", bn("5"), onError)
    await vi.advanceTimersByTimeAsync(UNDO_TIME)
    undo()
    await vi.runAllTimersAsync()

    expect(sent()).toEqual(["0>5"])
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it("drops the changes that build on the one that is undone", async () => {
    const undo = changeNominator("1", Gamemode.Osu, "0", bn("5"), vi.fn())
    changeNominator("1", Gamemode.Osu, "5", bn("6"), vi.fn())
    changeNominator("1", Gamemode.Osu, "6", bn("7"), vi.fn())
    changeNominator("1", Gamemode.Taiko, "0", bn("5"), vi.fn())
    undo()
    await vi.runAllTimersAsync()

    expect(sent()).toEqual(["0>5"])
    expect(request.mock.calls[0][0].url).toBe("/v2/beatmap/1/taiko/nominator")
  })

  it("is undone only once", async () => {
    const undo = changeNominator("1", Gamemode.Osu, "0", bn("5"), vi.fn())
    await vi.advanceTimersByTimeAsync(UNDO_TIME)
    undo()
    undo()
    await vi.runAllTimersAsync()

    expect(sent()).toEqual(["0>5", "5>0"])
  })

  it("keeps changes to other open spots when a removal is undone", async () => {
    const undo = changeNominator("1", Gamemode.Osu, "4", NOBODY, vi.fn())
    changeNominator("1", Gamemode.Osu, "0", bn("6"), vi.fn())
    undo()
    await vi.runAllTimersAsync()

    expect(sent()).toEqual(["0>6"])
  })

  it("sends what is still waiting and asks to stay when the page closes", async () => {
    changeNominator("1", Gamemode.Osu, "0", bn("5"), vi.fn())
    const leaving = new Event("beforeunload", {cancelable: true})
    window.dispatchEvent(leaving)
    await vi.runAllTimersAsync()

    expect(leaving.defaultPrevented).toBe(true)
    expect(sent()).toEqual(["0>5"])
  })

  it("lets the page close when nothing is waiting", () => {
    const leaving = new Event("beforeunload", {cancelable: true})
    window.dispatchEvent(leaving)

    expect(leaving.defaultPrevented).toBe(false)
  })
})
