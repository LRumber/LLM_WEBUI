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
