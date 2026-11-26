import {createContext, useContext} from "react";

export type OpenAdd = (link?: string) => void

export const AddContext = createContext<OpenAdd>(() => {})

/** Open the window to add a beatmap, with a link filled in when given */
export const useAddBeatmap = () => useContext(AddContext)
