// @vitest-environment jsdom
import {beforeEach, describe, expect, it, vi} from "vitest";
import type {AxiosRequestConfig} from "axios";

const backend = vi.fn()
vi.mock("axios", () => ({default: {create: vi.fn(() => ({request: backend}))}}))

const {loadSession, logOutSession, request} = await import("./client")
const {getSession} = await import("../utils/session")

const session = (csrfToken: string) => ({
  user: {osuId: "1", username: "BN", gamemodes: []},
  permission: {osuRole: "Nominator", roles: ["VIEWER", "EDITOR"]},
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

  it("treats a backend that can't be reached as nobody logged in", async () => {
    await logIn()
    backend.mockRejectedValueOnce(new Error("offline"))
    await loadSession()

    expect(getSession()).toBeUndefined()
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

    expect(await request({method: "DELETE", url: "/v2/beatmap/1/delete"})).toBe("done")
    expect(headersOf(3)).toEqual({"X-CSRF-TOKEN": "new"})
  })

  it("does not try again when the role is missing", async () => {
    await logIn()
    backend.mockRejectedValueOnce(refused(403)).mockResolvedValueOnce({status: 200, data: session("token")})

    await expect(request({method: "DELETE", url: "/v2/beatmap/1/delete"})).rejects.toEqual(refused(403))
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
