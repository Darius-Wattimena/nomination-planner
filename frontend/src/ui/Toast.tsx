import {createContext, ReactNode, useCallback, useContext, useRef, useState} from "react";
import {FaCircleCheck, FaCircleExclamation, FaXmark} from "react-icons/fa6";

type ToastKind = "success" | "error"
type ShowToast = (message: string, kind?: ToastKind) => void

const ToastContext = createContext<ShowToast>(() => {})

export const useToast = () => useContext(ToastContext)

export function ToastProvider({children}: {children: ReactNode}) {
  const [toasts, setToasts] = useState<{id: number, message: string, kind: ToastKind}[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => setToasts(all => all.filter(it => it.id !== id)), [])

  const show = useCallback<ShowToast>((message, kind = "success") => {
    const id = nextId.current++
    setToasts(all => [...all, {id, message, kind}])
    window.setTimeout(() => dismiss(id), kind === "error" ? 8000 : 4000)
  }, [dismiss])

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(toast => (
          <div key={toast.id} className={`toast toast-${toast.kind}`}>
            {toast.kind === "success" ? <FaCircleCheck/> : <FaCircleExclamation/>}
            <span>{toast.message}</span>
            <button className="icon-button" aria-label="Dismiss" onClick={() => dismiss(toast.id)}><FaXmark/></button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
