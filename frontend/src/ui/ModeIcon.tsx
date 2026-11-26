import type {ComponentType, SVGProps} from "react";
import OsuLogo from "../assets/osu.svg?react";
import TaikoLogo from "../assets/taiko.svg?react";
import CatchLogo from "../assets/catch.svg?react";
import ManiaLogo from "../assets/mania.svg?react";
import {GAMEMODE_NAMES, GAMEMODES} from "../Constants";
import {Gamemode} from "../models/Types";

const LOGOS: Record<Gamemode, ComponentType<SVGProps<SVGSVGElement>>> = {
  [Gamemode.Osu]: OsuLogo,
  [Gamemode.Taiko]: TaikoLogo,
  [Gamemode.Catch]: CatchLogo,
  [Gamemode.Mania]: ManiaLogo
}

export default function ModeIcon({mode}: {mode: Gamemode}) {
  const Logo = LOGOS[mode]
  return (
    <span className="mode-icon" role="img" aria-label={GAMEMODE_NAMES[mode]} title={GAMEMODE_NAMES[mode]}>
      <Logo aria-hidden/>
    </span>
  )
}

interface ModePickerProps {
  selected: Gamemode[]
  onChange: (modes: Gamemode[]) => void
  label: string
}

export function ModePicker({selected, onChange, label}: ModePickerProps) {
  return (
    <div className="chips" role="group" aria-label={label}>
      {GAMEMODES.map(mode => {
        const active = selected.includes(mode)
        return (
          <button
            key={mode}
            type="button"
            className="chip"
            aria-label={GAMEMODE_NAMES[mode]}
            aria-pressed={active}
            onClick={() => onChange(active ? selected.filter(it => it !== mode) : [...selected, mode])}
          >
            <ModeIcon mode={mode}/>{GAMEMODE_NAMES[mode]}
          </button>
        )
      })}
    </div>
  )
}
