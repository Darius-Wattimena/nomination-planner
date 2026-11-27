import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {discussionUrl, parseBeatmap, parseOsuLink} from "./osuLink";

describe("parseBeatmap", () => {
  it("reads links and bare IDs", () => {
    expect(parseBeatmap(" 123456 ")).toEqual({id: "123456", mode: undefined})
    expect(parseBeatmap("https://osu.ppy.sh/beatmapsets/42#taiko/1")).toEqual({id: "42", mode: Gamemode.Taiko})
    expect(parseOsuLink("123456")).toBeUndefined()
  })

  it("leaves other text alone", () => {
    expect(parseBeatmap("camellia")).toBeUndefined()
    expect(parseBeatmap("7")).toBeUndefined()
  })

  it("links to the discussion", () => {
    expect(discussionUrl("42")).toBe("https://osu.ppy.sh/beatmapsets/42/discussion")
  })
})
