import {createContext, useContext} from "react";

export const HelpContext = createContext<() => void>(() => {})

/** Open the explanation of the planner */
export const useHelp = () => useContext(HelpContext)
