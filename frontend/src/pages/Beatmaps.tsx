import {useEffect, useState} from "react";
import {FaPlus} from "react-icons/fa6";
import AddBeatmapDialog from "../beatmaps/AddBeatmapDialog";
import BeatmapDetails from "../beatmaps/BeatmapDetails";
import BeatmapRow from "../beatmaps/BeatmapRow";
import FilterBar from "../beatmaps/FilterBar";
import {PAGE_SIZES, useListState} from "../beatmaps/filters";
import Pagination from "../beatmaps/Pagination";
import {Beatmap, BeatmapPage} from "../models/Types";
import Api from "../resources/Api";
import {useQuery} from "../resources/client";
import PageHeader from "../ui/PageHeader";
import {EmptyState} from "../ui/States";
import {canEdit, useSession} from "../utils/session";

const TITLES: Record<BeatmapPage, string> = {
  PENDING: "Pending beatmaps",
  RANKED: "Ranked beatmaps",
  GRAVEYARD: "Graveyard"
}

export default function Beatmaps({page}: {page: BeatmapPage}) {
  const session = useSession()
  const {state, filter, update, clear, isFiltered} = useListState(page, session?.user)
  const [adding, setAdding] = useState(false)

  const total = useQuery<number>(Api.countBeatmaps(filter))
  const beatmaps = useQuery<Beatmap[]>(Api.fetchBeatmaps(filter, state.pageNumber, state.limit))

  function reload() {
    total.reload()
    beatmaps.reload()
  }

  // Go back to a page that exists, for example after deleting the last beatmap of a page
  useEffect(() => {
    if (total.loading || total.error || total.data === undefined) return
    const lastPage = Math.max(1, Math.ceil(total.data / PAGE_SIZES[state.limit]))
    if (state.pageNumber > lastPage) update({pageNumber: lastPage})
  }, [total.data, total.loading, total.error, state.pageNumber, state.limit])

  const count = total.data ?? 0

  return (
    <>
      <PageHeader
        title={TITLES[page]}
        subtitle={total.data !== undefined ? `${count} ${count === 1 ? "beatmap" : "beatmaps"}` : " "}
      >
        {page === "PENDING" && canEdit(session) &&
          <button className="button button-primary" onClick={() => setAdding(true)}><FaPlus/>Add beatmap</button>
        }
      </PageHeader>

      <FilterBar page={page} state={state} update={update} clear={clear} isFiltered={isFiltered} beatmaps={beatmaps.data ?? []}/>

      {beatmaps.error &&
        <p className="alert alert-error" role="alert">
          Could not load the beatmaps. <button className="link" onClick={reload}>Try again</button>
        </p>
      }
      {!beatmaps.data && beatmaps.loading && <Skeleton/>}
      {beatmaps.data && beatmaps.data.length === 0 && !beatmaps.loading && !beatmaps.error &&
        <EmptyState title="No beatmaps found">
          {isFiltered
            ? <><p className="muted">Nothing matches the current filters.</p><button className="button" onClick={clear}>Clear filters</button></>
            : <p className="muted">There is nothing here yet.</p>
          }
        </EmptyState>
      }
      {beatmaps.data && beatmaps.data.length > 0 &&
        <>
          <ul className="beatmaps" aria-busy={beatmaps.loading}>
            {beatmaps.data.map(beatmap =>
              <BeatmapRow key={beatmap.osuId} beatmap={beatmap} onOpen={() => update({map: beatmap.osuId})} onChanged={reload}/>
            )}
          </ul>
          <Pagination
            total={count}
            pageNumber={state.pageNumber}
            limit={state.limit}
            onPage={pageNumber => {
              update({pageNumber})
              window.scrollTo({top: 0})
            }}
            onLimit={limit => update({limit})}
          />
        </>
      }

      <BeatmapDetails id={state.map} onClose={() => update({map: undefined})} onChanged={reload}/>
      <AddBeatmapDialog
        open={adding}
        onClose={() => setAdding(false)}
        onAdded={id => {
          setAdding(false)
          reload()
          update({map: id})
        }}
      />
    </>
  )
}

function Skeleton() {
  return (
    <ul className="beatmaps" aria-label="Loading beatmaps" aria-busy>
      {Array.from({length: 6}, (_, index) => <li key={index} className="beatmap skeleton"/>)}
    </ul>
  )
}
