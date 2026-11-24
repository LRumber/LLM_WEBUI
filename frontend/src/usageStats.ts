export interface UsageNumbers {
  requestCount: number
  inputTokens: number
  outputTokens: number
  failedRequestCount?: number
}
export function sumUsage(rows: UsageNumbers[]) {
  return rows.reduce<Required<UsageNumbers>>((sum, row) => ({
    requestCount: sum.requestCount + row.requestCount,
    inputTokens: sum.inputTokens + row.inputTokens,
    outputTokens: sum.outputTokens + row.outputTokens,
    failedRequestCount: sum.failedRequestCount + (row.failedRequestCount ?? 0),
  }), { requestCount: 0, inputTokens: 0, outputTokens: 0, failedRequestCount: 0 })
}
export function groupUsage<T extends UsageNumbers & { source: string }>(rows: T[], key: (row: T) => string) {
  const groups = new Map<string, T[]>()
  for (const row of rows) {
    if (row.source !== 'chat' && row.source !== 'api') continue
    const id = key(row)
    groups.set(id, [...(groups.get(id) ?? []), row])
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([key, items]) => ({
    key, chat: sumUsage(items.filter(row => row.source === 'chat')),
    api: sumUsage(items.filter(row => row.source === 'api')), total: sumUsage(items),
  }))
}

export function monthlyUsage<T extends UsageNumbers & { source: string; month: string }>(rows: T[], month: string) {
  const existing = new Map(groupUsage(rows, row => row.month).map(row => [row.key, row]))
  const [year, end] = month.split('-').map(Number)
  return Array.from({ length: 12 }, (_, i) => {
    const date = new Date(Date.UTC(year!, end! - 12 + i, 1))
    const key = date.toISOString().slice(0, 7)
    return existing.get(key) ?? { key, api: sumUsage([]), chat: sumUsage([]), total: sumUsage([]) }
  })
}
