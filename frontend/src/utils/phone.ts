import {useSyncExternalStore} from "react";

const query = window.matchMedia("(max-width: 560px)")

function subscribe(onChange: () => void) {
  query.addEventListener("change", onChange)
  return () => query.removeEventListener("change", onChange)
}

export const usePhone = () => useSyncExternalStore(subscribe, () => query.matches)
