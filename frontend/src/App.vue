<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import {
  Archive,
  Bot,
  Check,
  Copy,
  Database,
  FileText,
  Library,
  Menu,
  MessageSquarePlus,
  Mic,
  PanelLeftClose,
  Paperclip,
  Search,
  Send,
  Settings,
  ShieldCheck,
  Square,
  Users,
} from 'lucide-vue-next'

type Role = 'user' | 'assistant'

interface ChatMessage {
  role: Role
  content: string
  error?: boolean
}

interface ModelInfo {
  key: string
  display_name: string
  capabilities: string[]
}

interface StreamEvent {
  type: 'metadata' | 'delta' | 'usage' | 'done' | 'error'
  content?: string
  message?: string
  conversation_id?: string
  message_id?: string
  prompt_tokens?: number
  completion_tokens?: number
  total_tokens?: number
}

interface ConversationSummary {
  id: string
  title: string
  model: string
  messageCount: number
  preview: string
  updatedAt: string
}

interface PersistedMessage {
  role: Role
  content: string
  status: string
  inputTokens: number
  outputTokens: number
}

const isWorkspace = computed(() => window.location.pathname.startsWith('/app'))
const prompt = ref('')
const models = ref<ModelInfo[]>([])
const selectedModel = ref('deepseek-chat')
const sidebarOpen = ref(false)
const messages = ref<ChatMessage[]>([])
const conversations = ref<ConversationSummary[]>([])
const conversationId = ref<string | null>(null)
const isGenerating = ref(false)
const usage = ref({ prompt: 0, completion: 0, total: 0 })
const conversationArea = ref<HTMLElement | null>(null)
const promptInput = ref<HTMLTextAreaElement | null>(null)
const copiedMessageIndex = ref<number | null>(null)
const serviceOnline = ref(false)
let abortController: AbortController | null = null

marked.setOptions({ gfm: true, breaks: true })

const selectedModelName = computed(() => {
  return models.value.find((model) => model.key === selectedModel.value)?.display_name ?? 'DeepSeek Chat'
})

const login = () => {
  window.location.href = '/api/auth/login'
}

const renderMarkdown = (content: string) => {
  return DOMPurify.sanitize(marked.parse(content) as string)
}

const copyMessage = async (content: string, index: number) => {
  await navigator.clipboard.writeText(content)
  copiedMessageIndex.value = index
  window.setTimeout(() => {
    if (copiedMessageIndex.value === index) copiedMessageIndex.value = null
  }, 1600)
}

const resizeComposer = () => {
  const textarea = promptInput.value
  if (!textarea) return
  textarea.style.height = 'auto'
  textarea.style.height = `${Math.min(textarea.scrollHeight, 180)}px`
}

const loadModels = async () => {
  try {
    const response = await fetch('/api/models')
    serviceOnline.value = response.ok
    if (!response.ok) return
    models.value = await response.json()
    if (models.value.length && !models.value.some((model) => model.key === selectedModel.value)) {
      selectedModel.value = models.value[0].key
    }
  } catch {
    models.value = []
    serviceOnline.value = false
  }
}

const loadConversations = async () => {
  try {
    const response = await fetch('/api/conversations')
    if (response.ok) conversations.value = await response.json()
  } catch {
    conversations.value = []
  }
}

const openConversation = async (conversation: ConversationSummary) => {
  if (isGenerating.value) return
  const response = await fetch(`/api/conversations/${conversation.id}/messages`)
  if (!response.ok) return
  const persisted: PersistedMessage[] = await response.json()
  conversationId.value = conversation.id
  selectedModel.value = conversation.model
  messages.value = persisted
    .filter((message) => message.content)
    .map((message) => ({
      role: message.role,
      content: message.content,
      error: message.status === 'failed',
    }))
  const lastAssistant = [...persisted].reverse().find((message) => message.role === 'assistant')
  usage.value = lastAssistant
    ? {
        prompt: lastAssistant.inputTokens,
        completion: lastAssistant.outputTokens,
        total: lastAssistant.inputTokens + lastAssistant.outputTokens,
      }
    : { prompt: 0, completion: 0, total: 0 }
  sidebarOpen.value = false
  await scrollToBottom()
}

const scrollToBottom = async () => {
  await nextTick()
  if (conversationArea.value) {
    conversationArea.value.scrollTop = conversationArea.value.scrollHeight
  }
}

const applyStreamEvent = (event: StreamEvent, assistantIndex: number) => {
  const assistant = messages.value[assistantIndex]
  if (event.type === 'metadata' && event.conversation_id) {
    conversationId.value = event.conversation_id
  }
  if (event.type === 'delta' && event.content) {
    assistant.content += event.content
    scrollToBottom()
  } else if (event.type === 'usage') {
    usage.value = {
      prompt: event.prompt_tokens ?? 0,
      completion: event.completion_tokens ?? 0,
      total: event.total_tokens ?? 0,
    }
  } else if (event.type === 'error') {
    assistant.error = true
    assistant.content = event.message || '请求模型失败，请稍后重试。'
  }
}

const consumeEventStream = async (response: Response, assistantIndex: number) => {
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''

    for (const block of blocks) {
      const dataLine = block.split('\n').find((line) => line.startsWith('data:'))
      if (!dataLine) continue
      applyStreamEvent(JSON.parse(dataLine.slice(5).trim()), assistantIndex)
    }
  }
}

const sendPrompt = async (preset?: string) => {
  const content = (preset ?? prompt.value).trim()
  if (!content || isGenerating.value) return

  const userMessage: ChatMessage = { role: 'user', content }
  const assistantMessage: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(userMessage, assistantMessage)
  const assistantIndex = messages.value.length - 1
  prompt.value = ''
  await nextTick()
  resizeComposer()
  usage.value = { prompt: 0, completion: 0, total: 0 }
  isGenerating.value = true
  abortController = new AbortController()
  await scrollToBottom()

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: conversationId.value,
        model: selectedModel.value,
        content,
      }),
      signal: abortController.signal,
    })
    if (!response.ok) {
      const errorBody = await response.json().catch(() => null)
      const detail = Array.isArray(errorBody?.detail)
        ? errorBody.detail.map((item: { msg?: string }) => item.msg).filter(Boolean).join('；')
        : errorBody?.detail
      throw new Error(detail || `请求失败（${response.status}）`)
    }
    await consumeEventStream(response, assistantIndex)
  } catch (error) {
    const assistant = messages.value[assistantIndex]
    if ((error as Error).name === 'AbortError') {
      if (!assistant.content) assistant.content = '已停止生成。'
    } else {
      assistant.error = true
      assistant.content = (error as Error).message || '无法连接后端服务。'
    }
  } finally {
    isGenerating.value = false
    abortController = null
    await loadConversations()
    await scrollToBottom()
  }
}

const stopGeneration = () => abortController?.abort()

const newConversation = () => {
  abortController?.abort()
  messages.value = []
  conversationId.value = null
  prompt.value = ''
  nextTick(resizeComposer)
  usage.value = { prompt: 0, completion: 0, total: 0 }
}

const handleComposerKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendPrompt()
  }
}

onMounted(() => {
  if (isWorkspace.value) {
    loadModels()
    loadConversations()
  }
})
</script>

<template>
  <main v-if="!isWorkspace" class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <div class="brand-mark"><Bot :size="28" /></div>
      <p class="product-name">离线智能工作台</p>
      <h1 id="login-title">统一身份认证</h1>
      <p class="login-copy">使用组织账号进入对话、知识库与智能应用平台。</p>
      <button class="primary-button login-button" type="button" @click="login">
        <ShieldCheck :size="18" />
        统一登录
      </button>
      <p class="login-note">账号与权限由统一身份认证中心管理</p>
    </section>
  </main>

  <main v-else class="workspace-shell">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="关闭侧栏" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="sidebar-header">
        <div class="compact-brand"><span class="brand-symbol"><Bot :size="18" /></span><span>智能工作台</span></div>
        <button class="icon-button mobile-only" title="关闭侧栏" @click="sidebarOpen = false"><PanelLeftClose :size="19" /></button>
      </div>

      <button class="new-chat" type="button" @click="newConversation">
        <MessageSquarePlus :size="18" />
        新建对话
      </button>

      <nav class="main-nav" aria-label="主导航">
        <a class="nav-item active" href="#"><Bot :size="18" />对话</a>
        <a class="nav-item" href="#"><Library :size="18" />知识库</a>
        <a class="nav-item" href="#"><Users :size="18" />团队空间</a>
        <a class="nav-item" href="#"><Archive :size="18" />智能应用</a>
      </nav>

      <div class="history-section">
        <div class="section-label">
          <span>最近</span>
          <button class="icon-button" title="搜索对话"><Search :size="16" /></button>
        </div>
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          class="history-item"
          :class="{ 'active-history': conversation.id === conversationId }"
          type="button"
          :title="conversation.preview"
          @click="openConversation(conversation)"
        >
          {{ conversation.title || '未命名对话' }}
        </button>
        <p v-if="!conversations.length" class="history-empty">暂无对话</p>
      </div>

      <div class="sidebar-footer">
        <a class="nav-item" href="#"><Settings :size="18" />设置</a>
        <div class="user-row">
          <div class="avatar">管</div>
          <div class="user-meta"><strong>开发用户</strong><span>本地调试模式</span></div>
        </div>
      </div>
    </aside>

    <section class="chat-workspace">
      <header class="topbar">
        <button class="icon-button mobile-only" title="打开菜单" @click="sidebarOpen = true"><Menu :size="20" /></button>
        <label class="model-selector">
          <select v-model="selectedModel" :disabled="isGenerating" aria-label="选择模型">
            <option v-if="!models.length" value="deepseek-chat">DeepSeek Chat</option>
            <option v-for="model in models" :key="model.key" :value="model.key">{{ model.display_name }}</option>
          </select>
        </label>
        <div class="topbar-actions">
          <span class="service-status" :class="{ offline: !serviceOnline }"><span></span>{{ serviceOnline ? '服务正常' : '服务离线' }}</span>
          <span v-if="usage.total" class="token-usage">{{ usage.total }} tokens</span>
        </div>
      </header>

      <div ref="conversationArea" class="conversation-area" :class="{ 'has-messages': messages.length }">
        <div v-if="!messages.length" class="empty-state">
          <div class="empty-icon"><Bot :size="24" /></div>
          <h1>有什么可以帮忙的？</h1>
          <p>{{ selectedModelName }} 已就绪</p>
          <div class="starter-grid">
            <button type="button" @click="sendPrompt('请介绍一下你能帮我完成哪些工作。')"><Bot :size="17" />介绍你能完成的工作</button>
            <button type="button" @click="sendPrompt('请帮我制定一个清晰的项目实施计划。')"><FileText :size="17" />制定项目实施计划</button>
            <button type="button" @click="sendPrompt('请用简洁的语言解释大模型 RAG 的工作原理。')"><Database :size="17" />解释 RAG 工作原理</button>
          </div>
        </div>

        <div v-else class="message-list">
          <article v-for="(message, index) in messages" :key="index" class="message" :class="message.role">
            <div v-if="message.role === 'assistant'" class="message-avatar"><Bot :size="17" /></div>
            <div class="message-column">
              <div class="message-body" :class="{ error: message.error }">
                <div v-if="message.content && message.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(message.content)"></div>
                <p v-else-if="message.content">{{ message.content }}</p>
                <div v-else class="typing-indicator" aria-label="模型正在生成"><i></i><i></i><i></i></div>
              </div>
              <div v-if="message.role === 'assistant' && message.content" class="message-actions">
                <button class="message-action" type="button" title="复制回答" @click="copyMessage(message.content, index)">
                  <Check v-if="copiedMessageIndex === index" :size="15" />
                  <Copy v-else :size="15" />
                </button>
              </div>
            </div>
          </article>
        </div>
      </div>

      <footer class="composer-wrap">
        <div class="composer">
          <textarea
            ref="promptInput"
            v-model="prompt"
            rows="1"
            placeholder="给智能工作台发送消息"
            :disabled="isGenerating"
            @input="resizeComposer"
            @keydown="handleComposerKeydown"
          ></textarea>
          <div class="composer-toolbar">
            <div class="input-tools">
              <button class="icon-button" title="上传文件或图片" disabled><Paperclip :size="19" /></button>
              <button class="icon-button" title="语音输入" disabled><Mic :size="19" /></button>
              <button class="knowledge-button" type="button" disabled><Library :size="16" />知识库</button>
            </div>
            <button v-if="isGenerating" class="send-button stop-button" type="button" title="停止生成" @click="stopGeneration"><Square :size="15" /></button>
            <button v-else class="send-button" type="button" title="发送" :disabled="!prompt.trim()" @click="sendPrompt()"><Send :size="18" /></button>
          </div>
        </div>
        <p class="composer-note">内容由 AI 生成，请核实重要信息</p>
      </footer>
    </section>
  </main>
</template>
