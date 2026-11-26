import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {mapper, user} from "../test/builders";
import {choosesOwnModes, modesOf, roleModes} from "./users";

describe("modes of a user", () => {
  const nat = user("1", {mania: "NominationAssessment", osu: "Nominator"})

  it("uses the modes with a role, in the usual order", () => {
    expect(roleModes(nat)).toEqual([Gamemode.Osu, Gamemode.Mania])
    expect(modesOf({...nat, interests: [Gamemode.Taiko]})).toEqual([Gamemode.Osu, Gamemode.Mania])
    expect(choosesOwnModes(nat)).toBe(false)
  })

  it("uses the chosen modes of someone without a role", () => {
    const chosen = {...mapper, interests: [Gamemode.Mania, Gamemode.Catch]}
    expect(choosesOwnModes(chosen)).toBe(true)
    expect(modesOf(chosen)).toEqual([Gamemode.Catch, Gamemode.Mania])
    // A mode listed as mapper is no role
    expect(modesOf({...user("2", {taiko: "Mapper"}), interests: [Gamemode.Osu]})).toEqual([Gamemode.Osu])
  })

  it("has no modes without a choice or a user", () => {
    expect(modesOf(mapper)).toEqual([])
    expect(modesOf(undefined)).toEqual([])
    expect(choosesOwnModes(undefined)).toBe(false)
  })
})
