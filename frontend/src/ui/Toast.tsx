import type {ReactNode} from "react";
import {useCallback, useEffect, useRef, useState} from "react";
import {UNDO_TIME} from "../resources/planner";
import {FaXmark} from "react-icons/fa6";
import type {ShowToast, ToastOptions} from "./useToast";
import {ToastContext} from "./useToast";

type Shown = ToastOptions & {id: number, message: string}

export function ToastProvider({children}: {children: ReactNode}) {
  const [toasts, setToasts] = useState<Shown[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => setToasts(all => all.filter(it => it.id !== id)), [])

  const show = useCallback<ShowToast>((message, options = {}) => {
    const id = nextId.current++
    setToasts(all => {
      // Older messages make room, but never one that can still be undone
      let extra = all.length - 2
      return [...all.filter(it => it.undo || extra-- <= 0), {id, message, ...options}]
    })
  }, [])

  return (
    <ToastContext.Provider value={show}>
      {children}
      {/* Always there, so screen readers hear what is added to it. Errors interrupt by themselves. */}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(toast => <Toast key={toast.id} toast={toast} onDismiss={dismiss}/>)}
      </div>
    </ToastContext.Provider>
  )
}

/** Goes away by itself, but not while the mouse or the keyboard is on it */
function Toast({toast: {id, message, kind, undo}, onDismiss}: {toast: Shown, onDismiss: (id: number) => void}) {
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const left = useRef(undo ? UNDO_TIME : kind === "error" ? 8000 : 4000)
  const paused = hovered || focused

  useEffect(() => {
    if (paused) return
    const started = Date.now()
    const timer = window.setTimeout(() => onDismiss(id), left.current)
    return () => {
      window.clearTimeout(timer)
      left.current = Math.max(1000, left.current - (Date.now() - started))
    }
  }, [paused, id, onDismiss])

  return (
    <div className={`toast toast-${kind ?? "success"}`} role={kind === "error" ? "alert" : undefined}
         onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
         onFocus={() => setFocused(true)} onBlur={event => setFocused(event.currentTarget.contains(event.relatedTarget))}>
      <span>{message}</span>
      {undo &&
        <button className="link" onClick={() => {
          onDismiss(id)
          undo()
        }}>Undo</button>
      }
      <button className="icon-button" aria-label="Dismiss" onClick={() => onDismiss(id)}><FaXmark/></button>
    </div>
  )
}
