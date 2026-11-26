import {createContext, useContext} from "react";

export interface ToastOptions {
  kind?: "success" | "error"
  undo?: () => void
}

export type ShowToast = (message: string, options?: ToastOptions) => void

export const ToastContext = createContext<ShowToast>(() => {})

/** Show a short message at the bottom of the page */
export const useToast = () => useContext(ToastContext)
