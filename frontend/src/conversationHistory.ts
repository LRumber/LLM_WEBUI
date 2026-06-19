export interface ConversationSummary {
  id: string
  title: string
  model: string
  messageCount: number
  preview: string
  updatedAt: string
}

export interface ConversationGroup {
  label: '今天' | '近 7 天' | '更早'
  conversations: ConversationSummary[]
}

/** Filters history by visible text and groups it into stable calendar buckets. */
export const groupConversations = (
  conversations: ConversationSummary[],
  query: string,
  now = new Date(),
): ConversationGroup[] => {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startOfWeek = new Date(startOfToday)
  startOfWeek.setDate(startOfWeek.getDate() - 6)

  const groups: ConversationGroup[] = [
    { label: '今天', conversations: [] },
    { label: '近 7 天', conversations: [] },
    { label: '更早', conversations: [] },
  ]

  conversations
    .filter((conversation) => {
      if (!normalizedQuery) return true
      return `${conversation.title} ${conversation.preview}`.toLocaleLowerCase().includes(normalizedQuery)
    })
    .forEach((conversation) => {
      const updatedAt = new Date(conversation.updatedAt)
      if (!Number.isNaN(updatedAt.getTime()) && updatedAt >= startOfToday) {
        groups[0].conversations.push(conversation)
      } else if (!Number.isNaN(updatedAt.getTime()) && updatedAt >= startOfWeek) {
        groups[1].conversations.push(conversation)
      } else {
        groups[2].conversations.push(conversation)
      }
    })

  return groups.filter((group) => group.conversations.length > 0)
}
