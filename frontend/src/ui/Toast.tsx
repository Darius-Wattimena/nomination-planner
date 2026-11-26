import type {ReactNode} from "react";
import {useCallback, useRef, useState} from "react";
import {UNDO_TIME} from "../resources/planner";
import {FaXmark} from "react-icons/fa6";
import type {ShowToast, ToastOptions} from "./useToast";
import {ToastContext} from "./useToast";

export function ToastProvider({children}: {children: ReactNode}) {
  const [toasts, setToasts] = useState<(ToastOptions & {id: number, message: string})[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => setToasts(all => all.filter(it => it.id !== id)), [])

  const show = useCallback<ShowToast>((message, options = {}) => {
    const id = nextId.current++
    setToasts(all => [...all.slice(-2), {id, message, ...options}])
    window.setTimeout(() => dismiss(id), options.undo ? UNDO_TIME : options.kind === "error" ? 8000 : 4000)
  }, [dismiss])

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(({id, message, kind, undo}) => (
          <div key={id} className={`toast toast-${kind ?? "success"}`}>
            <span>{message}</span>
            {undo &&
              <button className="link" onClick={() => {
                dismiss(id)
                undo()
              }}>Undo</button>
            }
            <button className="icon-button" aria-label="Dismiss" onClick={() => dismiss(id)}><FaXmark/></button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
