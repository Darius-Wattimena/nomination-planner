import {describe, expect, it} from "vitest";
import type {BeatmapEvent} from "../models/Types";
import {BeatmapStatus, Gamemode} from "../models/Types";
import {user} from "../test/builders";
import {describeEvent, timeAgo} from "./historyText";

const rory = user("1", {}, "Rory")
const editor = user("2", {}, "Editor")
const event = (changes: Partial<BeatmapEvent>): BeatmapEvent => ({type: "NoteChanged", at: "2026-11-27T00:00:00Z", gamemodes: [], ...changes})

describe("describeEvent", () => {
  it("tells who joined or left themselves, or who someone else added or removed", () => {
    expect(describeEvent(event({type: "NominatorJoined", user: rory, nominator: rory, gamemodes: [Gamemode.Taiko]}))).toBe("Rory joined osu!taiko")
    expect(describeEvent(event({type: "NominatorJoined", user: editor, nominator: rory, gamemodes: [Gamemode.Osu]}))).toBe("Editor added Rory to osu!")
    expect(describeEvent(event({type: "NominatorLeft", user: rory, nominator: rory, gamemodes: [Gamemode.Mania]}))).toBe("Rory left osu!mania")
    expect(describeEvent(event({type: "NominatorLeft", user: editor, nominator: rory, gamemodes: [Gamemode.Mania]}))).toBe("Editor removed Rory from osu!mania")
    expect(describeEvent(event({type: "NominatorReplaced", user: editor, nominator: rory, oldNominator: editor, gamemodes: [Gamemode.Osu]})))
      .toBe("Editor replaced Editor with Rory in osu!")
  })

  it("tells who added the beatmap or a mode, and changed the note", () => {
    expect(describeEvent(event({type: "Added", user: rory, gamemodes: [Gamemode.Osu, Gamemode.Taiko, Gamemode.Catch]}))).toBe("Rory added it for osu!, osu!taiko and osu!catch")
    expect(describeEvent(event({type: "Added", user: rory}))).toBe("Rory added it")
    expect(describeEvent(event({type: "Added", gamemodes: [Gamemode.Taiko]}))).toBe("Imported from osu! for osu!taiko")
    expect(describeEvent(event({type: "ModeAdded", user: rory, gamemodes: [Gamemode.Catch]}))).toBe("Rory added osu!catch")
    expect(describeEvent(event({type: "ModeRemoved", user: rory, gamemodes: [Gamemode.Catch]}))).toBe("Rory removed osu!catch")
    expect(describeEvent(event({type: "NoteChanged"}))).toBe("Someone changed the note")
  })

  it("tells what happened on osu!, with who did it where known", () => {
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "NOMINATE", user: rory, gamemodes: [Gamemode.Taiko]}))).toBe("Rory nominated osu!taiko on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "NOMINATE"}))).toBe("Someone nominated on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "QUALIFY"}))).toBe("Qualified on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "RANK"}))).toBe("Ranked on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "DISQUALIFY", user: rory}))).toBe("Rory disqualified it on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "DISQUALIFY"}))).toBe("Disqualified on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "NOMINATION_RESET", user: rory}))).toBe("Rory reset the nominations on osu!")
    expect(describeEvent(event({type: "StatusChanged", osuEvent: "NOMINATION_RESET"}))).toBe("The nominations were reset on osu!")
  })

  it("tells who changed the status on the planner, and synced", () => {
    expect(describeEvent(event({type: "StatusChanged", user: editor, status: BeatmapStatus.Graved}))).toBe("Editor changed the status to Graved")
    expect(describeEvent(event({type: "Synced", user: editor}))).toBe("Editor synced it with osu!")
    expect(describeEvent(event({type: "Synced", status: BeatmapStatus.Qualified}))).toBe("Synced with osu!, now Qualified")
  })
})

describe("timeAgo", () => {
  const now = new Date("2026-11-27T12:00:00Z").getTime()
  const before = (minutes: number) => new Date(now - minutes * 60_000).toISOString()

  it("tells how long ago in words", () => {
    expect(timeAgo(before(0), now)).toBe("just now")
    expect(timeAgo(before(1), now)).toBe("1 minute ago")
    expect(timeAgo(before(59), now)).toBe("59 minutes ago")
    expect(timeAgo(before(60 * 5), now)).toBe("5 hours ago")
    expect(timeAgo(before(60 * 24 * 3), now)).toBe("3 days ago")
    expect(timeAgo(before(60 * 24 * 20), now)).toBe("2 weeks ago")
    expect(timeAgo(before(60 * 24 * 90), now)).toBe("3 months ago")
    expect(timeAgo(before(60 * 24 * 800), now)).toBe("2 years ago")
    expect(timeAgo("not a date", now)).toBe("")
  })
})
