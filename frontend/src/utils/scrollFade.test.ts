import {describe, expect, it} from "vitest";
import {fadeOf} from "./scrollFade";

describe("fadeOf", () => {
  it("fades the edges with more to scroll to", () => {
    expect(fadeOf(0, 300, 300)).toBeUndefined()
    expect(fadeOf(0, 500, 300)).toBe("end")
    expect(fadeOf(100, 500, 300)).toBe("both")
    expect(fadeOf(200, 500, 300)).toBe("start")
    // Rounding leaves a pixel
    expect(fadeOf(199, 500, 300)).toBe("start")
  })
})
