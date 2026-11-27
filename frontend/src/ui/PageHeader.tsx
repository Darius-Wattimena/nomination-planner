import type {ReactNode} from "react";
import {useTitle} from "./useTitle";

interface PageHeaderProps {
  title: string
  /** For the tab, when it says more than the title */
  tabTitle?: string
  subtitle?: ReactNode
  children?: ReactNode
}

export default function PageHeader({title, tabTitle, subtitle, children}: PageHeaderProps) {
  useTitle(tabTitle ?? title)

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
