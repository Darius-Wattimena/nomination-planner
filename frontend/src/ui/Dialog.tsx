import {ReactNode, useState} from "react";
import Modal from "react-modal";
import {FaXmark} from "react-icons/fa6";

interface DialogProps {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}

export default function Dialog({open, onClose, title, children}: DialogProps) {
  return (
    <Modal
      isOpen={open}
      onRequestClose={onClose}
      contentLabel={title}
      className="dialog"
      overlayClassName="overlay"
    >
      <header className="dialog-header">
        <h2>{title}</h2>
        <button className="icon-button" aria-label="Close" onClick={onClose}><FaXmark/></button>
      </header>
      {children}
    </Modal>
  )
}

interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => Promise<void>
  title: string
  action: string
  danger?: boolean
  children: ReactNode
}

export function ConfirmDialog({open, onClose, onConfirm, title, action, danger, children}: ConfirmDialogProps) {
  const [busy, setBusy] = useState(false)

  async function confirm() {
    setBusy(true)
    try {
      await onConfirm()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onClose={() => !busy && onClose()} title={title}>
      <div className="dialog-body">{children}</div>
      <footer className="dialog-footer">
        <button className="button button-ghost" disabled={busy} onClick={onClose}>Cancel</button>
        <button className={`button ${danger ? "button-danger" : "button-primary"}`} disabled={busy} onClick={confirm}>
          {busy ? "Working…" : action}
        </button>
      </footer>
    </Dialog>
  )
}
