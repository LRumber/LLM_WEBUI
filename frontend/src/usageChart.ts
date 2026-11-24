export interface UsageChartRow { key: string; label: string; api: number; chat: number }
export function compactUsage(value: number): string {
  if (value >= 100_000_000) return `${Number((value / 100_000_000).toFixed(2))}亿`
  if (value >= 10_000) return `${Number((value / 10_000).toFixed(2))}万`
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
// Keep zero at the baseline and one a full interval above it, then use decades.
export function usageScale(value: number): number {
  return value <= 1 ? Math.max(0, value) : 1 + Math.log10(value)
}
export function usageTicks(max: number): number[] {
  const exponent = Math.max(1, Math.ceil(Math.log10(Math.max(1, max))))
  return [0, ...Array.from({ length: exponent + 1 }, (_, i) => 10 ** i)]
}
export function modelDisplayName(key: string, models: { key: string; display_name: string }[]): string {
  return models.find(model => model.key === key)?.display_name?.trim() || key
}
