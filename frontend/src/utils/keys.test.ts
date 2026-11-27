// @vitest-environment jsdom
import {describe, expect, it} from "vitest";
import {isShortcut, isTyping, pressesFocused} from "./keys";

const press = (key: string, target: HTMLElement, options: KeyboardEventInit = {}) => {
  let event: KeyboardEvent | undefined
  target.addEventListener("keydown", it => event = it, {once: true})
  target.dispatchEvent(new KeyboardEvent("keydown", {key, bubbles: true, ...options}))
  return event!
}

describe("keys", () => {
  it("knows when somebody is typing", () => {
    expect(isTyping(document.createElement("input"))).toBe(true)
    expect(isTyping(document.createElement("textarea"))).toBe(true)
    expect(isTyping(document.createElement("select"))).toBe(true)
    expect(isTyping(document.createElement("button"))).toBe(false)
    expect(isTyping(null)).toBe(false)
    const editable = document.createElement("div")
    editable.contentEditable = "true"
    // jsdom doesn't work it out by itself
    Object.defineProperty(editable, "isContentEditable", {value: true})
    expect(isTyping(editable)).toBe(true)
  })

  it("only takes keys on their own, outside of fields", () => {
    const div = document.body.appendChild(document.createElement("div"))
    const input = document.body.appendChild(document.createElement("input"))
    expect(isShortcut(press("/", div))).toBe(true)
    expect(isShortcut(press("/", input))).toBe(false)
    expect(isShortcut(press("o", div, {ctrlKey: true}))).toBe(false)
  })

  it("leaves Space on a button to the button", () => {
    const button = document.body.appendChild(document.createElement("button"))
    const div = document.body.appendChild(document.createElement("div"))
    expect(pressesFocused(press(" ", button))).toBe(true)
    expect(pressesFocused(press(" ", div))).toBe(false)
    expect(pressesFocused(press("ArrowLeft", button))).toBe(false)
  })
})
