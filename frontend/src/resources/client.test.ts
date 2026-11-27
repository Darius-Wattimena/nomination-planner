// @vitest-environment jsdom
import {beforeEach, describe, expect, it, vi} from "vitest";
import type {AxiosRequestConfig} from "axios";

const backend = vi.fn()
vi.mock("axios", () => ({default: {create: vi.fn(() => ({request: backend}))}}))

const {loadSession, logOutSession, problemOf, request} = await import("./client")
const {getSession, isSessionUnreachable} = await import("../utils/session")

const session = (csrfToken: string) => ({
  user: {osuId: "1", username: "BN", gamemodes: []},
  permission: {roles: ["VIEWER", "EDITOR"]},
  csrfToken
})

const refused = (status: number) => ({response: {status}})
const headersOf = (call: number) => (backend.mock.calls[call][0] as AxiosRequestConfig).headers

async function logIn(csrfToken = "token") {
  backend.mockResolvedValueOnce({status: 200, data: session(csrfToken)})
  await loadSession()
}

beforeEach(() => {
  backend.mockReset()
})

describe("loadSession", () => {
  it("knows who is logged in", async () => {
    await logIn()

    expect(getSession()?.user?.username).toBe("BN")
    expect(backend).toHaveBeenCalledWith({method: "GET", url: "/v2/session"})
  })

  it("knows nobody is logged in", async () => {
    await logIn()
    backend.mockResolvedValueOnce({status: 204, data: ""})
    await loadSession()

    expect(getSession()).toBeUndefined()
  })

  it("tells the backend can't be reached instead of logging out", async () => {
    await logIn()
    backend.mockRejectedValueOnce(new Error("offline"))
    await loadSession()

    expect(isSessionUnreachable()).toBe(true)
    expect(getSession()?.user?.username).toBe("BN")
  })

  it("counts a failing backend as unreachable, until it answers again", async () => {
    backend.mockRejectedValueOnce(refused(502))
    await loadSession()
    expect(isSessionUnreachable()).toBe(true)

    await logIn()
    expect(isSessionUnreachable()).toBe(false)
  })

  it("knows nobody is logged in when the backend refuses", async () => {
    await logIn()
    backend.mockRejectedValueOnce(refused(401))
    await loadSession()

    expect(getSession()).toBeUndefined()
    expect(isSessionUnreachable()).toBe(false)
  })
})

describe("request", () => {
  it("only sends the token of the session along with changes", async () => {
    await logIn()
    backend.mockResolvedValue({status: 200, data: "done"})

    expect(await request({method: "GET", url: "/v2/planner"})).toBe("done")
    expect(await request({method: "PATCH", url: "/v2/beatmap/1/note", headers: {"Content-Type": "text/plain"}})).toBe("done")

    expect(headersOf(1)).toEqual({})
    expect(headersOf(2)).toEqual({"Content-Type": "text/plain", "X-CSRF-TOKEN": "token"})
  })

  it("finds out who is logged in when the backend no longer knows them", async () => {
    await logIn()
    backend.mockRejectedValueOnce(refused(401)).mockResolvedValueOnce({status: 204, data: ""})

    await expect(request({method: "GET", url: "/v2/planner"})).rejects.toEqual(refused(401))
    expect(getSession()).toBeUndefined()
  })

  it("tries a change again with the new token of the session", async () => {
    await logIn("old")
    backend
      .mockRejectedValueOnce(refused(403))
      .mockResolvedValueOnce({status: 200, data: session("new")})
      .mockResolvedValueOnce({status: 200, data: "done"})

    expect(await request({method: "DELETE", url: "/v2/beatmap/1"})).toBe("done")
    expect(headersOf(3)).toEqual({"X-CSRF-TOKEN": "new"})
  })

  it("does not try again when the role is missing", async () => {
    await logIn()
    backend.mockRejectedValueOnce(refused(403)).mockResolvedValueOnce({status: 200, data: session("token")})

    await expect(request({method: "DELETE", url: "/v2/beatmap/1"})).rejects.toEqual(refused(403))
    expect(backend).toHaveBeenCalledTimes(3)
  })

  it("leaves other errors to the page", async () => {
    backend.mockRejectedValueOnce(refused(500))

    await expect(request({url: "/v2/planner"})).rejects.toEqual(refused(500))
    expect(backend).toHaveBeenCalledTimes(1)
  })
})

describe("logOutSession", () => {
  it("logs out, also when the backend can't be reached", async () => {
    await logIn()
    backend.mockRejectedValueOnce(new Error("offline"))

    await expect(logOutSession()).rejects.toThrow("offline")
    expect(getSession()).toBeUndefined()
    expect(localStorage.getItem("sessionChanged")).not.toBeNull()
  })
})

describe("problemOf", () => {
  it("reads the detail of a ProblemDetail", () => {
    expect(problemOf({response: {status: 409, data: {title: "Conflict", detail: "Already on the planner"}}})).toBe("Already on the planner")
    expect(problemOf({response: {status: 500, data: ""}})).toBeUndefined()
    expect(problemOf(new Error("offline"))).toBeUndefined()
  })
})
