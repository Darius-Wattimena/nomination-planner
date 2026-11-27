// @vitest-environment jsdom
import {beforeEach, describe, expect, it, vi} from "vitest";
import {readStored, store} from "../utils/storage";

beforeEach(() => {
  localStorage.clear()
  vi.resetModules()
})

describe("saved views", () => {
  it("leaves out what isn't a view", async () => {
    store("savedViews", [{name: "Mine", query: "is:mine"}, {name: " ", query: ""}, {name: "No query"}, null])
    const {getSavedViews} = await import("./savedViews")

    expect(getSavedViews()).toEqual([{name: "Mine", query: "is:mine"}])
  })

  it("puts a deleted view back where it was, also once the page is gone", async () => {
    store("savedViews", [{name: "A", query: "a"}, {name: "B", query: "b"}, {name: "C", query: "c"}])
    const {deleteView, getSavedViews, saveView} = await import("./savedViews")

    const undo = deleteView({name: "B", query: "b"})
    saveView({name: "D", query: "d"})
    expect(readStored("savedViews")).toEqual([{name: "A", query: "a"}, {name: "C", query: "c"}, {name: "D", query: "d"}])

    undo()
    expect(getSavedViews().map(it => it.name)).toEqual(["A", "B", "C", "D"])
    expect(readStored("savedViews")).toEqual(getSavedViews())
  })

  it("doesn't put a view back twice", async () => {
    const {deleteView, getSavedViews, saveView} = await import("./savedViews")
    saveView({name: "A", query: "a"})

    const undo = deleteView({name: "A", query: "a"})
    saveView({name: "A", query: "other"})
    undo()

    expect(getSavedViews()).toEqual([{name: "A", query: "other"}])
  })
})
