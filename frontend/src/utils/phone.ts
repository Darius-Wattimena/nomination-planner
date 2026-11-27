import {useSyncExternalStore} from "react";

// The same as the @media (max-width: 560px) block at the end of styles/app.css, change both together
const query = window.matchMedia("(max-width: 560px)")

function subscribe(onChange: () => void) {
  query.addEventListener("change", onChange)
  return () => query.removeEventListener("change", onChange)
}

export const usePhone = () => useSyncExternalStore(subscribe, () => query.matches)

/** Focusing a field on a phone or tablet opens the keyboard over what you wanted to see */
export const focusesByItself = () => window.matchMedia("(pointer: fine)").matches
