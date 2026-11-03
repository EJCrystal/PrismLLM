const USAGE_BUCKET_WIDTH_MS: Record<string, number> = {
  hour: 3_600_000,
  day: 86_400_000,
  week: 7 * 86_400_000,
}

export function usageChartBucketRange(
  report: { from: number; to: number; granularity: string },
  bucketStart: number,
): { from: number; to: number } | null {
  const bucketWidth = USAGE_BUCKET_WIDTH_MS[report.granularity] || 86_400_000
  const from = Math.max(report.from, bucketStart)
  const to = Math.min(report.to, bucketStart + bucketWidth)
  return to > from ? { from, to } : null
}

export function usageChartScale(maximum: number): { max: number; ticks: number[] } {
  if (!Number.isFinite(maximum) || maximum <= 0) return { max: 1, ticks: [1, .75, .5, .25, 0] }
  const raw = maximum / 4
  const power = 10 ** Math.floor(Math.log10(raw))
  const multiple = [1, 2, 5, 10].find(value => value * power >= raw) || 10
  const step = multiple * power
  const count = Math.ceil(maximum / step)
  const max = Number((step * count).toPrecision(12))
  return { max, ticks: Array.from({ length: count + 1 }, (_, index) => Number(((count - index) * step).toPrecision(12))) }
}

export function usageChartIndex(clientX: number, left: number, width: number, count: number): number {
  if (count <= 0) return -1
  if (count === 1 || !Number.isFinite(width) || width <= 0) return 0
  const ratio = ((clientX - left) / width - 8 / 640) / (624 / 640)
  return Math.max(0, Math.min(count - 1, Math.round(ratio * (count - 1))))
}

export function usageChartPoint(index: number, count: number, amount: number, max: number) {
  return { x: 8 + (count <= 1 ? 312 : index / (count - 1) * 624), y: 142 - Math.max(0, amount) / (max > 0 ? max : 1) * 134 }
}
