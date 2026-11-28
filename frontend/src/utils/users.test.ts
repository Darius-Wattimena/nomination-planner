import {describe, expect, it} from "vitest";
import {Gamemode} from "../models/Types";
import {mapper, user} from "../test/builders";
import {canNominate, choosesOwnModes, displayName, highestRole, modesOf, roleModes} from "./users";

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

describe("displayName", () => {
  it("names restricted users, who are stored without their name", () => {
    expect(displayName(user("1", {}, "RESTRICTED"))).toBe("Restricted user")
    expect(displayName(user("2", {}, "Greaper"))).toBe("Greaper")
  })
})

describe("former nominators", () => {
  const former = user("5", {osu: "Former", taiko: "Nominator"})

  it("shows them as former, but only lets them view", () => {
    expect(highestRole(former, Gamemode.Osu)).toBe("Former")
    expect(highestRole(former)).toBe("Nominator")
    expect(canNominate(former, Gamemode.Osu)).toBe(false)
    expect(canNominate(former, Gamemode.Taiko)).toBe(true)
    expect(roleModes(former)).toEqual([Gamemode.Taiko])
  })
})
