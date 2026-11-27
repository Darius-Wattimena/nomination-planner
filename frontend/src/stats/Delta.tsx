interface DeltaProps {
  value: number | undefined
  /** The same number in the previous range, nothing to compare to when undefined */
  before: number | undefined
  /** What it is compared to, like "the 4 weeks before" or "2025" */
  than: string | undefined
  /** Like days to ranked, where going down is the good direction */
  lowerIsBetter?: boolean
}

/** "+18 vs the 4 weeks before", with the sign and an arrow so it doesn't depend on the colour */
export default function Delta({value, before, than, lowerIsBetter}: DeltaProps) {
  if (value === undefined || before === undefined || !than) return null
  const change = Math.round((value - before) * 10) / 10
  const trend = change === 0 ? "same" : (change > 0) !== !!lowerIsBetter ? "good" : "bad"
  return (
    <span className="delta" data-trend={trend}>
      <span aria-hidden>{change > 0 ? "▲ " : change < 0 ? "▼ " : ""}</span>
      {change > 0 ? "+" : change < 0 ? "−" : "±"}{Math.abs(change)} vs {than}
    </span>
  )
}
