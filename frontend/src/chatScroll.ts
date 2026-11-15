/** Allow fractional-pixel rounding without confusing reading position with follow mode. */
export function isChatAtBottom(area: { scrollHeight: number; scrollTop: number; clientHeight: number }) {
  return Math.max(0, area.scrollHeight - area.clientHeight - area.scrollTop) <= 2
}
