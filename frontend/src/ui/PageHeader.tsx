import type {ReactNode} from "react";
import {useEffect} from "react";

interface PageHeaderProps {
  title: string
  subtitle?: ReactNode
  children?: ReactNode
}

export default function PageHeader({title, subtitle, children}: PageHeaderProps) {
  useEffect(() => {
    document.title = `${title} · Nomination Planner`
  }, [title])

  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {children}
    </header>
  )
}
