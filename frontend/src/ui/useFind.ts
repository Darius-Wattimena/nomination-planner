import {createContext, useContext} from "react";

export const FindContext = createContext<() => void>(() => {})

/** Open the search over all beatmaps and people */
export const useFind = () => useContext(FindContext)
