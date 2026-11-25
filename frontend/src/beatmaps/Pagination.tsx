import {FaAngleLeft, FaAngleRight, FaAnglesLeft, FaAnglesRight} from "react-icons/fa6";
import {PageLimit} from "../models/Types";
import {PAGE_SIZES} from "./filters";

interface PaginationProps {
  total: number
  pageNumber: number
  limit: PageLimit
  onPage: (pageNumber: number) => void
  onLimit: (limit: PageLimit) => void
}

export default function Pagination({total, pageNumber, limit, onPage, onLimit}: PaginationProps) {
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZES[limit]))

  return (
    <nav className="pagination" aria-label="Pages">
      <label className="muted">
        Per page{" "}
        <select value={limit} onChange={event => onLimit(event.target.value as PageLimit)}>
          {Object.entries(PAGE_SIZES).map(([key, size]) => <option key={key} value={key}>{size}</option>)}
        </select>
      </label>
      <div className="pagination-pages">
        <button className="icon-button" aria-label="First page" disabled={pageNumber <= 1} onClick={() => onPage(1)}><FaAnglesLeft/></button>
        <button className="icon-button" aria-label="Previous page" disabled={pageNumber <= 1} onClick={() => onPage(pageNumber - 1)}><FaAngleLeft/></button>
        <span>Page {pageNumber} of {lastPage}</span>
        <button className="icon-button" aria-label="Next page" disabled={pageNumber >= lastPage} onClick={() => onPage(pageNumber + 1)}><FaAngleRight/></button>
        <button className="icon-button" aria-label="Last page" disabled={pageNumber >= lastPage} onClick={() => onPage(lastPage)}><FaAnglesRight/></button>
      </div>
    </nav>
  )
}
