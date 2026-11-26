<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import {
  ElDropdown, ElDropdownItem, ElDropdownMenu, ElOption, ElProgress, ElSelect, ElTag,
} from 'element-plus'
import { marked, type Tokens } from 'marked'
import AdminDashboard from './AdminDashboard.vue'
import { defaultPlatformSettings, loadPlatformSettings, uploadPolicyError } from './platformSettings'
import ApiPlatformPage from './ApiPlatformPage.vue'
import KnowledgeBasePage from './KnowledgeBasePage.vue'
import TeamPage from './TeamPage.vue'
import FeedbackPanel from './FeedbackPanel.vue'
import { readChatEvents } from './chatStream'
import { isChatAtBottom } from './chatScroll'
import { groupConversations, type ConversationSummary } from './conversationHistory'
import { assessFileCompatibility } from './fileCompatibility'
import {
  Archive,
  ArrowDown,
  Bot,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Copy,
  Database,
  Download,
  FileText,
  GitBranch,
  Library,
  LockKeyhole,
  LogOut,
  Menu,
  MessageSquarePlus,
  Mic,
  PanelLeftClose,
  Paperclip,
  Pencil,
  Quote,
  Search,
  ArrowUp,
  Settings,
  ShieldCheck,
  Square,
  Terminal,
  RotateCcw,
  Trash2,
  Users,
  X,
} from 'lucide-vue-next'

// Domain contracts mirror the JSON returned by Spring Boot and the normalized SSE protocol.
type Role = 'user' | 'assistant'

/** Message rendered in the active browser conversation. */
interface ChatMessage {
  id?: string
  role: Role
  content: string
  statusText?: string
  error?: boolean
  editedAt?: string | null
  versionIndex?: number | null
  versionCount?: number | null
  attachments?: UploadedFile[]
  quotedText?: string
  reasoningContent?: string
  reasoningDurationMs?: number | null
  reasoningExpanded?: boolean
  citations?: MessageCitation[]
}

interface MessageCitation {
  number?: number
  chunkId?: string
  fileId: string
  filename: string
  pageNumber?: number | null
  titlePath?: string | null
  content: string
  score: number
}

interface KnowledgeBaseOption {
  id: string
  name: string
}

interface CurrentUser {
  id: number
  externalUserId?: number
  idNo?: string
  accountNumber: string
  displayName: string
  userNumber?: string
  email?: string
}

interface AuthOptions {
  devLoginEnabled: boolean
}

/** Model registration exposed through the Java proxy. */
interface ModelInfo {
  key: string
  display_name: string
  capabilities: string[]
  reasoning_mode?: 'none' | 'qwen3' | 'deepseek_v4' | 'deepseek_r1' | 'always'
  context_window?: number | null
}

/** Normalized event emitted by Spring Boot while forwarding an AI response. */
interface StreamEvent {
  type: 'metadata' | 'edit_metadata' | 'citations' | 'context_compaction' | 'reasoning_delta' | 'reasoning_done' | 'delta' | 'usage' | 'done' | 'error'
  status?: string
  content?: string
  message?: string
  conversation_id?: string
  message_id?: string
  version_index?: number
  prompt_tokens?: number
  completion_tokens?: number
  total_tokens?: number
  duration_ms?: number
  citations?: MessageCitation[]
}

/** Persisted message returned when restoring an existing conversation. */
interface PersistedMessage {
  id: string
  role: Role | 'system'
  content: string
  status: string
  inputTokens: number
  outputTokens: number
  editedAt?: string | null
  versionIndex?: number | null
  versionCount?: number | null
  attachments?: UploadedFile[]
  reasoningContent?: string | null
  reasoningDurationMs?: number | null
  citations?: MessageCitation[]
}

interface PromptTemplate {
  id: string
  name: string
  description: string
  systemPrompt: string
  scope: string
  enabled: boolean
}

interface PlatformAnnouncement {
  id: string
  version: string
  title: string
  body: string
  published: boolean
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

interface UploadedFile {
  id: string
  originalName: string
  mediaType: string
  status: 'uploaded' | 'processing' | 'ready' | 'failed'
}

/** Maps durable parser states to concise Element Plus status labels. */
const attachmentStatusLabel = (status: UploadedFile['status']) => ({
  uploaded: '等待解析', processing: '解析中', ready: '已接收', failed: '解析失败',
}[status])

/** Keeps attachment status colors consistent between the composer and sent messages. */
const attachmentTagType = (status: UploadedFile['status']) => {
  if (status === 'ready') return 'success'
  if (status === 'failed') return 'danger'
  return 'warning'
}

/** Formats persisted reasoning latency as a compact human-readable duration. */
const formatReasoningDuration = (durationMs?: number | null) => {
  if (!durationMs) return '正在思考...'
  return `已思考 ${(durationMs / 1000).toFixed(durationMs < 10_000 ? 1 : 0)} 秒`
}

/** Downloads an owned attachment through the backend without exposing MinIO credentials. */
const downloadAttachment = async (file: UploadedFile) => {
  const response = await fetch(`/api/files/${file.id}/download`)
  if (!response.ok) {
    window.alert('文件下载失败，请稍后重试。')
    return
  }
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = file.originalName
  anchor.click()
  URL.revokeObjectURL(url)
}


const downloadCitation = async (citation: MessageCitation) => {
  const response = await fetch(`/api/files/${citation.fileId}/download`)
  if (!response.ok) return window.alert('引用文件下载失败')
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = citation.filename
  anchor.click()
  URL.revokeObjectURL(url)
}

// Page and conversation state stays local; durable data always comes from Spring Boot.
const isKnowledge = computed(() => window.location.pathname.startsWith('/app/knowledge'))
const isTeam = computed(() => window.location.pathname.startsWith('/app/team'))
const isApiPlatform = computed(() => window.location.pathname.startsWith('/app/api-platform'))
const isWorkspace = computed(() => window.location.pathname.startsWith('/app') && !isKnowledge.value && !isTeam.value && !isApiPlatform.value)
const isUserApp = computed(() => window.location.pathname.startsWith('/app'))
const isAdmin = computed(() => window.location.pathname.startsWith('/admin'))
const userSessionChecked = ref(!isUserApp.value)
const currentUser = ref<CurrentUser | null>(null)
const authOptions = ref<AuthOptions>({ devLoginEnabled: false })
const platformSettings = ref({ ...defaultPlatformSettings })
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
const messageList = ref<HTMLElement | null>(null)
const promptInput = ref<HTMLTextAreaElement | null>(null)
const copiedMessageIndex = ref<number | null>(null)
const serviceOnline = ref(false)
const editingConversationId = ref<string | null>(null)
const editingTitle = ref('')
const editingMessageKey = ref<string | null>(null)
const editingMessageContent = ref('')
const branchingMessageIds = ref<Set<string>>(new Set())
const branchedMessageIds = ref<Set<string>>(new Set())
const switchingMessageVersionIds = ref<Set<string>>(new Set())
const historySearch = ref('')
const historySearchOpen = ref(false)
const historySearchInput = ref<HTMLInputElement | null>(null)
const promptTemplates = ref<PromptTemplate[]>([])
const knowledgeBases = ref<KnowledgeBaseOption[]>([])
const selectedKnowledgeBaseIds = ref<string[]>([])
const selectedTemplateId = ref('')
const templateManagerOpen = ref(false)
const editingTemplateId = ref<string | null>(null)
const templateForm = ref({ name: '', description: '', systemPrompt: '' })
const activeAnnouncement = ref<PlatformAnnouncement | null>(null)
const announcementHistory = ref<PlatformAnnouncement[]>([])
const announcementHistoryOpen = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const attachments = ref<UploadedFile[]>([])
const uploadingFile = ref(false)
const recordingVoice = ref(false)
const transcribingVoice = ref(false)
const voiceDurationSeconds = ref(0)
const thinkingEnabled = ref(false)
const followLatest = ref(true)
const atLatestReply = ref(true)
const expandedUserMessages = ref<Set<string>>(new Set())
const selectionPopover = ref<{ text: string; x: number; y: number } | null>(null)
const quotedSelection = ref<{ text: string } | null>(null)
let abortController: AbortController | null = null
let voiceStream: MediaStream | null = null
let voiceAudioContext: AudioContext | null = null
let voiceSource: MediaStreamAudioSourceNode | null = null
let voiceProcessor: ScriptProcessorNode | null = null
let voicePcmChunks: Float32Array[] = []
let voiceSampleRate = 16000
let voiceShouldTranscribe = false
let voiceTimer: number | null = null
const ANNOUNCEMENT_SEEN_PREFIX = 'lm-platform-announcement-seen:'
const USER_MESSAGE_COLLAPSE_LINES = 10
const USER_MESSAGE_COLLAPSE_CHARS = 700

const escapeHtml = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const normalizeCodeLanguage = (lang?: string) => {
  const raw = (lang ?? '').trim().split(/\s+/)[0].toLowerCase()
  if (!raw) return 'text'
  const aliases: Record<string, string> = {
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    py: 'python',
    sh: 'bash',
    shell: 'bash',
    yml: 'yaml',
    md: 'markdown',
    ps1: 'powershell',
  }
  return aliases[raw] ?? raw
}

const languageLabel = (lang: string) => ({
  bash: 'Bash',
  css: 'CSS',
  html: 'HTML',
  java: 'Java',
  javascript: 'JavaScript',
  json: 'JSON',
  markdown: 'Markdown',
  powershell: 'PowerShell',
  python: 'Python',
  sql: 'SQL',
  text: 'Text',
  typescript: 'TypeScript',
  vue: 'Vue',
  xml: 'XML',
  yaml: 'YAML',
}[lang] ?? lang.toUpperCase())

const commonKeywordSource = 'abstract|and|as|async|await|break|case|catch|class|const|continue|def|do|else|enum|except|export|extends|false|final|finally|for|from|function|if|implements|import|in|instanceof|interface|let|new|null|or|package|private|protected|public|return|static|super|switch|this|throw|throws|true|try|type|var|void|while|with|yield'

const highlightGenericCode = (code: string) => {
  const tokenPattern = new RegExp(
    `(\\/\\/.*$|#.*$)|("(?:\\\\.|[^"])*"|'(?:\\\\.|[^'])*'|\`(?:\\\\.|[^\`])*\`)|\\b(${commonKeywordSource})\\b|(-?\\b\\d+(?:\\.\\d+)?\\b)`,
    'gm',
  )
  let html = ''
  let cursor = 0
  for (const match of code.matchAll(tokenPattern)) {
    const index = match.index ?? 0
    html += escapeHtml(code.slice(cursor, index))
    const token = match[0]
    if (match[1]) html += `<span class="code-token comment">${escapeHtml(token)}</span>`
    else if (match[2]) html += `<span class="code-token string">${escapeHtml(token)}</span>`
    else if (match[3]) html += `<span class="code-token keyword">${escapeHtml(token)}</span>`
    else html += `<span class="code-token number">${escapeHtml(token)}</span>`
    cursor = index + token.length
  }
  html += escapeHtml(code.slice(cursor))
  return html
}

const highlightCode = (code: string, lang: string) => {
  let html = escapeHtml(code)
  if (['json', 'yaml', 'yml'].includes(lang)) {
    html = html
      .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="code-token property">$1</span>$2')
      .replace(/\b(true|false|null)\b/g, '<span class="code-token literal">$1</span>')
      .replace(/(-?\b\d+(?:\.\d+)?\b)/g, '<span class="code-token number">$1</span>')
    return html
  }
  if (['bash', 'shell', 'powershell'].includes(lang)) {
    html = html
      .replace(/(^|\n)(\s*#.*)/g, '$1<span class="code-token comment">$2</span>')
      .replace(/\b(cd|chmod|curl|docker|echo|export|git|grep|mkdir|npm|python|sudo|tar|unzip)\b/g, '<span class="code-token keyword">$1</span>')
      .replace(/(--?[a-zA-Z0-9][\w-]*)/g, '<span class="code-token property">$1</span>')
    return html
  }
  return highlightGenericCode(code)
}

// Configure GitHub-flavored Markdown once; every rendered result is sanitized below.
marked.use({
  gfm: true,
  breaks: true,
  renderer: {
    code(token: Tokens.Code) {
      const language = normalizeCodeLanguage(token.lang)
      const highlighted = highlightCode(token.text, language)
      return `<div class="code-block" data-code-language="${escapeHtml(language)}"><div class="code-block-header"><span>${escapeHtml(languageLabel(language))}</span><button type="button" class="code-copy-button" data-code-copy>复制</button></div><pre><code class="language-${escapeHtml(language)}">${highlighted}</code></pre></div>`
    },
  },
})

const selectedModelName = computed(() => {
  return models.value.find((model) => model.key === selectedModel.value)?.display_name ?? 'DeepSeek Chat'
})
const currentUserInitial = computed(() => currentUser.value?.displayName?.slice(0, 1) || '用')
const currentUserSubtitle = computed(() => {
  const user = currentUser.value
  if (!user) return ''
  return user.userNumber ? `工号 ${user.userNumber}` : user.accountNumber || user.idNo || ''
})
const voiceDurationLabel = computed(() => {
  const minutes = Math.floor(voiceDurationSeconds.value / 60).toString().padStart(2, '0')
  const seconds = (voiceDurationSeconds.value % 60).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
})
const selectedModelCapabilities = computed(() => {
  return models.value.find((model) => model.key === selectedModel.value)?.capabilities ?? []
})
const selectedModelContextWindow = computed(() => {
  return models.value.find((model) => model.key === selectedModel.value)?.context_window ?? null
})
const selectedModelReasoningMode = computed(() => {
  return models.value.find((model) => model.key === selectedModel.value)?.reasoning_mode ?? 'none'
})
const selectedModelSupportsThinking = computed(() => selectedModelReasoningMode.value === 'deepseek_v4')
const activeThinkingEnabled = computed(() => selectedModelSupportsThinking.value && thinkingEnabled.value)
const selectedTemplateName = computed(() => {
  const activeConversation = conversations.value.find((item) => item.id === conversationId.value)
  if (conversationId.value) return activeConversation?.promptTemplateName ?? '默认提示词'
  return promptTemplates.value.find((template) => template.id === selectedTemplateId.value)?.name
    ?? '默认提示词'
})
const selectedKnowledgeBaseNames = computed(() => {
  if (conversationId.value) {
    return conversations.value.find((item) => item.id === conversationId.value)?.knowledgeBaseNames ?? ''
  }
  return knowledgeBases.value
    .filter((item) => selectedKnowledgeBaseIds.value.includes(item.id))
    .map((item) => item.name)
    .join('、')
})

const estimateTextTokens = (text: string) => {
  const cjk = (text.match(/[\u3400-\u9fff]/g) ?? []).length
  const nonCjk = Math.max(0, text.length - cjk)
  return Math.ceil(cjk * 1.15 + nonCjk / 4)
}

const estimateConversationTokens = (nextContent: string) => {
  const history = messages.value
    .filter((message) => message.content)
    .slice(-40)
    .reduce((total, message) => total + estimateTextTokens(message.content) + 4, 0)
  return history + estimateTextTokens(nextContent) + 16
}

const ensureWithinModelContext = (content: string) => {
  const contextWindow = selectedModelContextWindow.value
  if (!contextWindow) return true
  const usablePromptTokens = Math.max(1, Math.floor(contextWindow * 0.8) - 512)
  const estimatedTokens = estimateConversationTokens(content)
  if (estimatedTokens <= usablePromptTokens) return true
  window.alert(`当前内容预计约 ${estimatedTokens} tokens，已超过模型可用上下文约 ${usablePromptTokens} tokens。请缩短输入、开启新对话，或切换更大上下文模型。`)
  return false
}

const conversationGroups = computed(() => {
  return groupConversations(conversations.value, historySearch.value)
})

/** Applies a sidebar prompt choice; an empty command always means the platform default. */
const selectPromptTemplate = (command: string | number | object) => {
  if (String(command) === '__manage__') {
    templateManagerOpen.value = true
    return
  }
  if (conversationId.value) return
  selectedTemplateId.value = String(command)
}

/** Converts the compact composer dropdown choice into the model request flag. */
const selectThinkingMode = (command: string | number | object) => {
  thinkingEnabled.value = String(command) === 'thinking'
}

/** Toggles knowledge spaces only before the first message locks the conversation snapshot. */
const selectKnowledgeBase = (command: string | number | object) => {
  if (conversationId.value) return
  const id = String(command)
  if (id === '__clear__') {
    selectedKnowledgeBaseIds.value = []
    return
  }
  selectedKnowledgeBaseIds.value = selectedKnowledgeBaseIds.value.includes(id)
    ? selectedKnowledgeBaseIds.value.filter((item) => item !== id)
    : [...selectedKnowledgeBaseIds.value, id]
}

/** Expands or collapses sidebar search and resets filtering when it closes. */
const toggleHistorySearch = async () => {
  historySearchOpen.value = !historySearchOpen.value
  if (!historySearchOpen.value) {
    historySearch.value = ''
    return
  }
  await nextTick()
  historySearchInput.value?.focus()
}

/** Starts the organization's server-managed OAuth2 redirect flow. */
const login = () => {
  window.location.href = '/api/auth/login'
}

const SSO_LOGOUT_URL = 'http://sso.sitp.ac.cn/uias/logout'
const LOCAL_LOGIN_URL = '/app'

const devLogin = async (user = 'DEV001') => {
  const response = await fetch(`/api/auth/dev-login?user=${encodeURIComponent(user)}`, { method: 'POST' })
  if (!response.ok) {
    window.alert('本机调试登录未启用，请确认后端 DEV_LOGIN_ENABLED=true')
    return
  }
  currentUser.value = await response.json()
  window.location.href = '/app'
}

const forwardOAuthCallback = () => {
  const params = new URLSearchParams(window.location.search)
  if (!params.has('code') && !params.has('error')) return false
  window.location.href = `/api/auth/oauth2/callback${window.location.search}`
  return true
}

const logout = async () => {
  try {
    await fetch('/api/auth/logout', { method: 'POST' })
  } finally {
    currentUser.value = null
    const popup = window.open(SSO_LOGOUT_URL, 'sitp-sso-logout', 'popup,width=420,height=320')
    window.setTimeout(() => {
      try {
        popup?.close()
      } catch {
        // Ignore cross-origin popup restrictions; the logout request has already been issued.
      }
      window.location.replace(LOCAL_LOGIN_URL)
    }, 900)
  }
}

/** Converts model Markdown to sanitized HTML safe for v-html rendering. */
const renderMarkdown = (content: string) => {
  return DOMPurify.sanitize(marked.parse(content) as string, {
    ADD_ATTR: ['data-code-copy', 'data-code-language'],
  })
}

const messageDisplayKey = (message: ChatMessage, index: number) => message.id ?? `${message.role}-${index}`

const shouldCollapseUserMessage = (message: ChatMessage) => {
  if (message.role !== 'user') return false
  const explicitLines = message.content.split(/\r\n|\r|\n/).length
  return explicitLines > USER_MESSAGE_COLLAPSE_LINES || message.content.length > USER_MESSAGE_COLLAPSE_CHARS
}

const isUserMessageExpanded = (message: ChatMessage, index: number) => {
  return expandedUserMessages.value.has(messageDisplayKey(message, index))
}

const toggleUserMessageExpanded = (message: ChatMessage, index: number) => {
  const next = new Set(expandedUserMessages.value)
  const key = messageDisplayKey(message, index)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedUserMessages.value = next
}

/** Copies one assistant answer and briefly changes the action icon to confirmation. */
const copyMessage = async (content: string, index: number) => {
  await navigator.clipboard.writeText(content)
  copiedMessageIndex.value = index
  window.setTimeout(() => {
    if (copiedMessageIndex.value === index) copiedMessageIndex.value = null
  }, 1600)
}

const handleConversationClick = async (event: MouseEvent) => {
  const target = event.target as HTMLElement | null
  const button = target?.closest<HTMLButtonElement>('[data-code-copy]')
  if (!button) return
  const block = button.closest('.code-block')
  const code = block?.querySelector('code')?.textContent ?? ''
  if (!code) return
  await navigator.clipboard.writeText(code)
  const previousLabel = button.textContent ?? '复制'
  button.textContent = '已复制'
  button.classList.add('copied')
  window.setTimeout(() => {
    button.textContent = previousLabel
    button.classList.remove('copied')
  }, 1400)
}

/** Grows the composer until its maximum height, then lets the textarea scroll. */
const resizeComposer = () => {
  const textarea = promptInput.value
  if (!textarea) return
  textarea.style.height = '0px'
  const nextHeight = Math.min(textarea.scrollHeight, 180)
  textarea.style.height = `${nextHeight}px`
  textarea.style.overflowY = textarea.scrollHeight > 180 ? 'auto' : 'hidden'
}

const focusComposer = async () => {
  await nextTick()
  promptInput.value?.focus()
}

const resizeMessageEditor = () => {
  const textarea = document.querySelector<HTMLTextAreaElement>('.user-message-editor textarea')
  if (!textarea) return
  textarea.style.height = '0px'
  const nextHeight = Math.min(textarea.scrollHeight, 180)
  textarea.style.height = `${nextHeight}px`
  textarea.style.overflowY = textarea.scrollHeight > 180 ? 'auto' : 'hidden'
}

watch(prompt, () => {
  void nextTick(() => {
    resizeComposer()
    window.requestAnimationFrame(resizeComposer)
  })
})

watch(selectedModelReasoningMode, (mode) => {
  if (mode === 'none') thinkingEnabled.value = false
})

const normalizeSpeechText = (value: string) => {
  return value.replace(/\s+/g, ' ').trim()
}

const appendSpeechText = (existing: string, recognized: string) => {
  const text = normalizeSpeechText(recognized)
  if (!text) return existing
  if (!existing.trim()) return text
  if (/\s$/.test(existing)) return `${existing}${text}`
  const last = existing.at(-1) ?? ''
  const first = text.at(0) ?? ''
  const joiner = /[\u4e00-\u9fff]/.test(last) || /[\u4e00-\u9fff]/.test(first) ? '' : ' '
  return `${existing}${joiner}${text}`
}

/** Loads selectable model registrations through the Java business-service boundary. */
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

/** Refreshes the current user's persisted conversation summaries. */
const loadConversations = async () => {
  try {
    const response = await fetch('/api/conversations')
    if (response.ok) conversations.value = await response.json()
  } catch {
    conversations.value = []
  }
}

/** Loads reusable system instructions without exposing templates owned by other users. */
const loadPromptTemplates = async () => {
  try {
    const response = await fetch('/api/prompt-templates')
    if (response.ok) promptTemplates.value = await response.json()
  } catch {
    promptTemplates.value = []
  }
}

/** Loads only knowledge spaces visible to the current user for new-conversation selection. */
const loadKnowledgeBases = async () => {
  try {
    const response = await fetch('/api/knowledge-bases')
    if (response.ok) knowledgeBases.value = await response.json()
  } catch {
    knowledgeBases.value = []
  }
}

/** Shows the latest published announcement once per browser and revision. */
const loadAnnouncement = async () => {
  try {
    const response = await fetch('/api/announcement/history')
    if (!response.ok) return
    const history: PlatformAnnouncement[] = await response.json()
    announcementHistory.value = history
    const latest = history[0]
    if (!latest) {
      activeAnnouncement.value = null
      return
    }
    const seenKey = `${ANNOUNCEMENT_SEEN_PREFIX}${latest.id}`
    activeAnnouncement.value = window.localStorage.getItem(seenKey) ? null : latest
    announcementHistoryOpen.value = false
  } catch {
    activeAnnouncement.value = null
  }
}

const dismissAnnouncement = () => {
  if (activeAnnouncement.value?.id) {
    window.localStorage.setItem(`${ANNOUNCEMENT_SEEN_PREFIX}${activeAnnouncement.value.id}`, '1')
  }
  activeAnnouncement.value = null
  announcementHistoryOpen.value = false
}

const selectAnnouncement = (announcement: PlatformAnnouncement) => {
  activeAnnouncement.value = announcement
  announcementHistoryOpen.value = false
}

const announcementDate = (announcement: PlatformAnnouncement) => {
  const value = announcement.publishedAt || announcement.updatedAt || announcement.createdAt
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}

const announcementVersion = (announcement: PlatformAnnouncement) => {
  return announcement.version || 'V1'
}

/** Polls durable processing state so extracted content is ready before a message can be sent. */
const waitForFileReady = async (fileId: string) => {
  for (let attempt = 0; attempt < 60; attempt++) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000))
    const response = await fetch('/api/files')
    if (!response.ok) return
    const files: UploadedFile[] = await response.json()
    const latest = files.find((file) => file.id === fileId)
    const current = attachments.value.find((file) => file.id === fileId)
    if (latest && current) Object.assign(current, latest)
    if (!latest || latest.status === 'ready' || latest.status === 'failed') return
  }
}

/** Opens the personal-template editor while system templates remain read-only. */
const editPromptTemplate = (template?: PromptTemplate) => {
  editingTemplateId.value = template?.id ?? null
  templateForm.value = {
    name: template?.name ?? '',
    description: template?.description ?? '',
    systemPrompt: template?.systemPrompt ?? '',
  }
}

/** Creates or updates a personal template through ownership-filtered APIs. */
const savePromptTemplate = async () => {
  const templateIdBeforeSave = editingTemplateId.value
  const payload = { ...templateForm.value, enabled: true }
  const response = await fetch(
    editingTemplateId.value ? `/api/prompt-templates/${editingTemplateId.value}` : '/api/prompt-templates',
    {
      method: editingTemplateId.value ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  )
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    window.alert(error?.message || '提示词模板保存失败，请检查名称和内容。')
    return
  }
  const created: PromptTemplate | null = templateIdBeforeSave ? null : await response.json()
  await loadPromptTemplates()
  if (!messages.value.length) selectedTemplateId.value = created?.id ?? templateIdBeforeSave ?? ''
  editPromptTemplate()
  templateManagerOpen.value = false
  if (messages.value.length) window.alert('模板已保存，将在下一次新建对话时生效。')
}

/** Disables an owned personal template after confirmation, preserving historical references. */
const deletePromptTemplate = async (template: PromptTemplate) => {
  if (!window.confirm(`确定删除“${template.name}”吗？`)) return
  const response = await fetch(`/api/prompt-templates/${template.id}`, { method: 'DELETE' })
  if (response.ok) {
    if (selectedTemplateId.value === template.id) selectedTemplateId.value = ''
    await loadPromptTemplates()
  }
}

/** Uploads selected files to private object storage before linking them to a chat message. */
const uploadFiles = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  if (!files.length) return
  uploadingFile.value = true
  try {
    platformSettings.value = await loadPlatformSettings()
    for (const file of files.slice(0, 20 - attachments.value.length)) {
      const compatibility = assessFileCompatibility(file, selectedModelCapabilities.value)
      const policyError = uploadPolicyError(file, platformSettings.value)
      if (policyError) { window.alert(`${file.name}：${policyError}`); continue }
      if (!compatibility.supported) {
        window.alert(`${file.name}：${compatibility.reason}`)
        continue
      }
      const body = new FormData()
      body.append('file', file)
      const response = await fetch('/api/files', { method: 'POST', body })
      if (response.ok) {
        const uploaded: UploadedFile = await response.json()
        attachments.value.push(uploaded)
        void waitForFileReady(uploaded.id)
      } else {
        const detail = await response.json().catch(() => null)
        window.alert(detail?.message || `${file.name} 上传失败`)
      }
    }
  } catch (error) {
    window.alert(error instanceof Error ? error.message : '文件上传失败，请重试')
  } finally {
    uploadingFile.value = false
    input.value = ''
  }
}

const clearVoiceTimer = () => {
  if (voiceTimer !== null) {
    window.clearInterval(voiceTimer)
    voiceTimer = null
  }
}

const stopVoiceTracks = () => {
  voiceProcessor?.disconnect()
  voiceSource?.disconnect()
  void voiceAudioContext?.close()
  voiceProcessor = null
  voiceSource = null
  voiceAudioContext = null
  voiceStream?.getTracks().forEach((track) => track.stop())
  voiceStream = null
}

const downsampleAudio = (samples: Float32Array, sourceRate: number, targetRate = 16000) => {
  if (sourceRate <= targetRate) return samples
  const ratio = sourceRate / targetRate
  const length = Math.floor(samples.length / ratio)
  const result = new Float32Array(length)
  for (let index = 0; index < length; index += 1) {
    const start = Math.floor(index * ratio)
    const end = Math.min(Math.floor((index + 1) * ratio), samples.length)
    let total = 0
    for (let cursor = start; cursor < end; cursor += 1) total += samples[cursor]
    result[index] = total / Math.max(end - start, 1)
  }
  return result
}

const encodeWav = (chunks: Float32Array[], sampleRate: number) => {
  const mergedLength = chunks.reduce((total, chunk) => total + chunk.length, 0)
  const merged = new Float32Array(mergedLength)
  let offset = 0
  chunks.forEach((chunk) => {
    merged.set(chunk, offset)
    offset += chunk.length
  })
  const pcm = downsampleAudio(merged, sampleRate)
  const buffer = new ArrayBuffer(44 + pcm.length * 2)
  const view = new DataView(buffer)
  const writeString = (position: number, value: string) => {
    for (let index = 0; index < value.length; index += 1) view.setUint8(position + index, value.charCodeAt(index))
  }
  writeString(0, 'RIFF')
  view.setUint32(4, 36 + pcm.length * 2, true)
  writeString(8, 'WAVE')
  writeString(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, Math.min(sampleRate, 16000), true)
  view.setUint32(28, Math.min(sampleRate, 16000) * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeString(36, 'data')
  view.setUint32(40, pcm.length * 2, true)
  let position = 44
  pcm.forEach((sample) => {
    const clamped = Math.max(-1, Math.min(1, sample))
    view.setInt16(position, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true)
    position += 2
  })
  return new Blob([buffer], { type: 'audio/wav' })
}

const transcribeVoiceBlob = async (audio: Blob) => {
  if (!audio.size) return
  transcribingVoice.value = true
  try {
    const body = new FormData()
    body.append('file', audio, 'recording.wav')
    const response = await fetch('/api/speech/transcribe', { method: 'POST', body })
    if (!response.ok) {
      const error = await response.json().catch(() => null)
      throw new Error(error?.message || '语音识别失败')
    }
    const result: { text?: string } = await response.json()
    const recognized = result.text?.trim()
    if (recognized) {
      prompt.value = appendSpeechText(prompt.value, recognized)
      await nextTick()
      resizeComposer()
      promptInput.value?.focus()
    }
  } catch (error) {
    window.alert(error instanceof Error ? error.message : '语音识别失败，请确认已配置内网语音识别模型。')
  } finally {
    transcribingVoice.value = false
  }
}

/** Starts a local browser recording; transcription is only sent after explicit confirmation. */
const startVoiceInput = async () => {
  if (recordingVoice.value || transcribingVoice.value) return
  const AudioContextConstructor = window.AudioContext
    || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!navigator.mediaDevices?.getUserMedia || !AudioContextConstructor) {
    window.alert('当前浏览器不支持本地录音，请使用新版 Chrome、Edge 或 Firefox。')
    return
  }
  try {
    voiceStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    voiceAudioContext = new AudioContextConstructor()
    voiceSampleRate = voiceAudioContext.sampleRate
    voiceSource = voiceAudioContext.createMediaStreamSource(voiceStream)
    voiceProcessor = voiceAudioContext.createScriptProcessor(4096, 1, 1)
    voicePcmChunks = []
    voiceShouldTranscribe = false
    voiceDurationSeconds.value = 0
    voiceProcessor.onaudioprocess = (event) => {
      if (!recordingVoice.value) return
      voicePcmChunks.push(new Float32Array(event.inputBuffer.getChannelData(0)))
    }
    voiceSource.connect(voiceProcessor)
    voiceProcessor.connect(voiceAudioContext.destination)
    recordingVoice.value = true
    voiceTimer = window.setInterval(() => {
      voiceDurationSeconds.value += 1
    }, 1000)
  } catch {
    window.alert('无法访问麦克风，请确认浏览器麦克风权限已允许。')
  }
}

const finishVoiceInput = () => {
  if (!recordingVoice.value) return
  voiceShouldTranscribe = true
  const audio = encodeWav(voicePcmChunks, voiceSampleRate)
  clearVoiceTimer()
  stopVoiceTracks()
  recordingVoice.value = false
  voicePcmChunks = []
  voiceShouldTranscribe = false
  void transcribeVoiceBlob(audio)
}

const cancelVoiceInput = () => {
  if (!recordingVoice.value) return
  voiceShouldTranscribe = false
  clearVoiceTimer()
  stopVoiceTracks()
  recordingVoice.value = false
  voicePcmChunks = []
}

/** Polls briefly because title generation intentionally runs after the first chat response. */
const scheduleTitleRefresh = () => {
  // Title generation runs asynchronously after the first answer is persisted.
  const refreshDelays = [1500, 4000, 8000]
  refreshDelays.forEach((delay) => window.setTimeout(loadConversations, delay))
}

/** Restores an existing conversation and its last reported token usage. */
const openConversation = async (conversation: ConversationSummary, forceBottom = true, restoreModel = true) => {
  if (isGenerating.value) {
    abortController?.abort()
    abortController = null
    isGenerating.value = false
  }
  const previousScrollTop = conversationArea.value?.scrollTop ?? 0
  const response = await fetch(`/api/conversations/${conversation.id}/messages`)
  if (!response.ok) {
    window.alert(`加载历史对话失败（${response.status}），请刷新后重试。`)
    return
  }
  const persisted: PersistedMessage[] = await response.json()
  conversationId.value = conversation.id
  if (restoreModel) selectedModel.value = conversation.model
  selectedTemplateId.value = conversation.promptTemplateId ?? ''
  messages.value = persisted
    .filter((message): message is PersistedMessage & { role: Role } =>
      (Boolean(message.content) || message.role === 'assistant') && message.role !== 'system')
    .map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content || '当前模型暂时不可用，请稍后重试或切换其他模型。',
      error: message.status === 'failed' || message.status === 'interrupted' || !message.content,
      editedAt: message.editedAt,
      versionIndex: message.versionIndex,
      versionCount: message.versionCount,
      attachments: message.attachments ?? [],
      reasoningContent: message.reasoningContent ?? '',
      reasoningDurationMs: message.reasoningDurationMs,
      reasoningExpanded: false,
      citations: message.citations ?? [],
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
  if (forceBottom) {
    followLatest.value = true
    await scrollToBottom(true)
  } else {
    await nextTick()
    if (conversationArea.value) conversationArea.value.scrollTop = previousScrollTop
  }
}

/** Enters inline title-edit mode for one sidebar conversation. */
const startRename = (conversation: ConversationSummary) => {
  editingConversationId.value = conversation.id
  editingTitle.value = conversation.title
}

/** Leaves inline title-edit mode without changing persisted data. */
const cancelRename = () => {
  editingConversationId.value = null
  editingTitle.value = ''
}

/** Persists a non-empty manual title, which takes precedence over async AI titles. */
const saveRename = async () => {
  const id = editingConversationId.value
  const title = editingTitle.value.trim()
  if (!id) return
  cancelRename()
  if (!title) return

  const response = await fetch(`/api/conversations/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
  if (response.ok) await loadConversations()
}

/** Replaces an owned user message inline; later messages remain superseded on the backend. */
const editMessage = (message: ChatMessage, index: number) => {
  if (!conversationId.value || !message.id || message.role !== 'user') return
  editingMessageKey.value = messageDisplayKey(message, index)
  editingMessageContent.value = message.content
  nextTick(() => resizeMessageEditor())
}

const cancelMessageEdit = () => {
  editingMessageKey.value = null
  editingMessageContent.value = ''
}

const branchConversation = async (message: ChatMessage, options: { silent?: boolean } = {}) => {
  if (!conversationId.value || !message.id || branchingMessageIds.value.has(message.id)) return null
  if (!options.silent && message.role !== 'assistant') {
    window.alert('只能从模型回复处创建分支。')
    return null
  }
  if (!options.silent && branchedMessageIds.value.has(message.id)) {
    window.alert('这个位置已经创建过分支了，请在左侧历史对话中查看。')
    return null
  }
  if (!options.silent && !window.confirm('将从这里创建一个新的对话分支，并切换到分支继续查看。确定创建吗？')) {
    return null
  }
  const nextBranching = new Set(branchingMessageIds.value)
  nextBranching.add(message.id)
  branchingMessageIds.value = nextBranching
  try {
    const response = await fetch(`/api/conversations/${conversationId.value}/branches`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messageId: message.id }),
    })
    if (!response.ok) {
      if (!options.silent) window.alert('创建分支失败，请稍后重试。')
      return null
    }
    const result: { conversationId: string } = await response.json()
    const created = new Set(branchedMessageIds.value)
    created.add(message.id)
    branchedMessageIds.value = created
    if (!options.silent) await loadConversations()
    if (!options.silent) {
      const branch = conversations.value.find((item) => item.id === result.conversationId)
      if (branch) await openConversation(branch)
    }
    return result.conversationId
  } finally {
    const next = new Set(branchingMessageIds.value)
    next.delete(message.id)
    branchingMessageIds.value = next
  }
}

const sendEditedMessage = async (message: ChatMessage, index: number) => {
  if (!conversationId.value || !message.id || message.role !== 'user') return
  const content = editingMessageContent.value.trim()
  if (!content) return
  if (content === message.content) return
  if (!ensureWithinModelContext(content) || isGenerating.value) return

  const currentConversationId = conversationId.value
  cancelMessageEdit()
  messages.value.splice(index, messages.value.length - index, {
    ...message, content, editedAt: new Date().toISOString(),
  }, {
    role: 'assistant', content: '', reasoningContent: '', reasoningExpanded: false, citations: [],
  })
  const assistantIndex = index + 1
  expandedUserMessages.value.delete(messageDisplayKey(message, index))
  usage.value = { prompt: 0, completion: 0, total: 0 }
  isGenerating.value = true
  abortController = new AbortController()
  followLatest.value = true
  await scrollToBottom(true)

  try {
    const response = await fetch(`/api/conversations/${currentConversationId}/messages/${message.id}/edit-and-regenerate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, model: selectedModel.value, thinkingEnabled: activeThinkingEnabled.value }),
      signal: abortController.signal,
    })
    if (!response.ok) {
      const detail = await response.json().catch(() => null)
      throw new Error(detail?.message || `编辑发送失败（${response.status}）`)
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
    const current = conversations.value.find((item) => item.id === conversationId.value)
    if (current && !messages.value[assistantIndex]?.error) await openConversation(current, followLatest.value, false)
    await scrollToBottom()
  }
}

const activateMessageVersion = async (message: ChatMessage, direction: -1 | 1) => {
  if (!conversationId.value || !message.id || message.role !== 'user') return
  const current = message.versionIndex ?? 1
  const total = message.versionCount ?? 1
  const target = current + direction
  if (target < 1 || target > total) return
  const next = new Set(switchingMessageVersionIds.value)
  next.add(message.id)
  switchingMessageVersionIds.value = next
  try {
    const response = await fetch(
      `/api/conversations/${conversationId.value}/messages/${message.id}/versions/${target}/activate`,
      { method: 'POST' },
    )
    if (!response.ok) {
      window.alert('切换历史版本失败，请稍后重试。')
      return
    }
    await loadConversations()
    const currentConversation = conversations.value.find((item) => item.id === conversationId.value)
    if (currentConversation) await openConversation(currentConversation, false)
  } finally {
    const after = new Set(switchingMessageVersionIds.value)
    after.delete(message.id)
    switchingMessageVersionIds.value = after
  }
}

const handleConversationSelection = () => {
  const selection = window.getSelection()
  const text = selection?.toString().trim()
  if (!selection || !text) {
    selectionPopover.value = null
    return
  }
  const range = selection.rangeCount ? selection.getRangeAt(0) : null
  const area = conversationArea.value
  if (!range || !area || !area.contains(range.commonAncestorContainer)) {
    selectionPopover.value = null
    return
  }
  const rect = range.getBoundingClientRect()
  selectionPopover.value = {
    text: text.length > 2000 ? `${text.slice(0, 2000)}...` : text,
    x: Math.min(window.innerWidth - 220, Math.max(16, rect.left)),
    y: Math.max(16, rect.top - 50),
  }
}

const quoteSelectedText = () => {
  if (!selectionPopover.value) return
  quotedSelection.value = { text: selectionPopover.value.text }
  selectionPopover.value = null
  window.getSelection()?.removeAllRanges()
  nextTick(() => promptInput.value?.focus())
}

const clearQuotedSelection = () => {
  quotedSelection.value = null
}

/** Streams a replacement for one persisted assistant answer without adding a duplicate user turn. */
const regenerateMessage = async (message: ChatMessage, index: number) => {
  if (!conversationId.value || !message.id || message.role !== 'assistant' || isGenerating.value) return
  message.content = ''
  message.error = false
  message.reasoningContent = ''
  isGenerating.value = true
  abortController = new AbortController()
  try {
    const response = await fetch(
      `/api/conversations/${conversationId.value}/messages/${message.id}/regenerate`,
      {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: selectedModel.value }), signal: abortController.signal,
      },
    )
    if (!response.ok) {
      const detail = await response.json().catch(() => null)
      throw new Error(detail?.message || `重新生成失败（${response.status}）`)
    }
    await consumeEventStream(response, index)
  } catch (error) {
    message.error = true
    message.content = (error as Error).name === 'AbortError' ? '已停止生成。' : (error as Error).message
  } finally {
    isGenerating.value = false
    abortController = null
    await loadConversations()
    const current = conversations.value.find((item) => item.id === conversationId.value)
    if (current && !message.error) await openConversation(current, true, false)
  }
}

/** Soft-deletes one conversation after an explicit destructive-action confirmation. */
const deleteConversation = async (conversation: ConversationSummary) => {
  if (!window.confirm(`确定删除“${conversation.title || '未命名对话'}”吗？`)) return
  const response = await fetch(`/api/conversations/${conversation.id}`, { method: 'DELETE' })
  if (!response.ok) return
  if (conversationId.value === conversation.id) newConversation()
  await loadConversations()
}

/** Tracks whether the reader is close enough to the bottom to resume automatic following. */
const handleConversationScroll = () => {
  const area = conversationArea.value
  if (!area) return
  atLatestReply.value = isChatAtBottom(area)
  if (atLatestReply.value) followLatest.value = true
}

const pauseFollowing = () => { followLatest.value = false }
const handleConversationWheel = (event: WheelEvent) => {
  if (event.deltaY < 0) pauseFollowing()
}
const handleConversationKeydown = (event: KeyboardEvent) => {
  if (['ArrowUp', 'PageUp', 'Home'].includes(event.key) || (event.key === ' ' && event.shiftKey)) pauseFollowing()
}

/** Keeps newest content visible only while the reader has not intentionally scrolled upward. */
const scrollToBottom = async (force = false) => {
  await nextTick()
  if (conversationArea.value) atLatestReply.value = isChatAtBottom(conversationArea.value)
  if (!force && !followLatest.value) return
  if (conversationArea.value) {
    conversationArea.value.scrollTop = conversationArea.value.scrollHeight
    handleConversationScroll()
  }
}

// Rendering Markdown, images and persisted history can resize content after a stream chunk.
watch([conversationArea, messageList], ([area, list], _previous, onCleanup) => {
  if (!area || !list || typeof ResizeObserver === 'undefined') return
  const observer = new ResizeObserver(() => { void scrollToBottom() })
  observer.observe(area)
  observer.observe(list)
  onCleanup(() => observer.disconnect())
}, { flush: 'post' })

/** Restores live following after the reader explicitly asks to return to the newest output. */
const jumpToLatest = async () => {
  followLatest.value = true
  await scrollToBottom(true)
}

/** Applies one normalized SSE event to reactive message, metadata, or usage state. */
const applyStreamEvent = (event: StreamEvent, assistantIndex: number) => {
  const assistant = messages.value[assistantIndex]
  if (event.type === 'metadata' && event.conversation_id) {
    conversationId.value = event.conversation_id
  }
  if (event.type === 'edit_metadata' && event.message_id) {
    const editedUser = messages.value[assistantIndex - 1]
    if (editedUser?.role === 'user') {
      editedUser.id = event.message_id
      editedUser.versionIndex = event.version_index ?? editedUser.versionIndex
    }
  } else if (event.type === 'citations') {
    assistant.citations = event.citations ?? []
  } else if (event.type === 'context_compaction') {
    assistant.statusText = event.message || (event.status === 'started' ? '正在压缩上下文，请稍等' : '上下文压缩完成，正在生成回复')
    scrollToBottom()
  } else if (event.type === 'reasoning_delta' && event.content) {
    assistant.statusText = undefined
    assistant.reasoningContent = (assistant.reasoningContent ?? '') + event.content
    scrollToBottom()
  } else if (event.type === 'reasoning_done') {
    assistant.reasoningDurationMs = event.duration_ms ?? null
  } else if (event.type === 'delta' && event.content) {
    assistant.statusText = undefined
    assistant.content += event.content
    scrollToBottom()
  } else if (event.type === 'usage') {
    usage.value = {
      prompt: event.prompt_tokens ?? 0,
      completion: event.completion_tokens ?? 0,
      total: event.total_tokens ?? 0,
    }
  } else if (event.type === 'error') {
    assistant.statusText = undefined
    assistant.error = true
    assistant.content = event.message || '请求模型失败，请稍后重试。'
  }
}

/** Incrementally decodes SSE blocks from the Fetch response body. */
const consumeEventStream = async (response: Response, assistantIndex: number) => {
  await readChatEvents(response, event => applyStreamEvent(event, assistantIndex))
  const assistant = messages.value[assistantIndex]
  if (!assistant.content && !assistant.error) {
    assistant.statusText = undefined
    assistant.error = true
    assistant.content = '未收到模型的有效回复，请重试或切换模型；若持续出现，请提交意见反馈。'
  }
}

/** Creates or continues a persisted conversation and consumes its streaming answer. */
const sendPrompt = async (preset?: string) => {
  const content = (preset ?? prompt.value).trim()
  if (!content || isGenerating.value) return
  const contentForModel = quotedSelection.value
    ? `请基于以下引用内容回答：\n\n> ${quotedSelection.value.text.replace(/\n/g, '\n> ')}\n\n${content}`
    : content
  if (!ensureWithinModelContext(contentForModel)) return
  if (attachments.value.some((file) => file.status !== 'ready')) {
    window.alert('当前输入超过模型上下文限制，请缩短后再发送。')
    return
  }
  const isFirstMessage = conversationId.value === null
  // Move a snapshot onto the outgoing message before clearing the composer attachment tray.
  const sentAttachments = attachments.value.map((file) => ({ ...file }))
  const attachmentIds = sentAttachments.map((file) => file.id)

  const userMessage: ChatMessage = {
    role: 'user',
    content,
    attachments: sentAttachments,
    quotedText: quotedSelection.value?.text,
  }
  const assistantMessage: ChatMessage = {
    role: 'assistant', content: '', reasoningContent: '', reasoningExpanded: false, citations: [],
  }
  messages.value.push(userMessage, assistantMessage)
  const assistantIndex = messages.value.length - 1
  prompt.value = ''
  quotedSelection.value = null
  attachments.value = []
  await nextTick()
  resizeComposer()
  usage.value = { prompt: 0, completion: 0, total: 0 }
  isGenerating.value = true
  abortController = new AbortController()
  followLatest.value = true
  await scrollToBottom(true)

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: conversationId.value,
        model: selectedModel.value,
        content,
        modelContent: contentForModel,
        templateId: isFirstMessage && selectedTemplateId.value ? selectedTemplateId.value : null,
        attachmentIds,
        knowledgeBaseIds: isFirstMessage ? selectedKnowledgeBaseIds.value : [],
        thinkingEnabled: activeThinkingEnabled.value,
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
    const current = conversations.value.find((conversation) => conversation.id === conversationId.value)
    if (current && !messages.value[assistantIndex]?.error) await openConversation(current, followLatest.value, false)
    if (isFirstMessage) scheduleTitleRefresh()
    await scrollToBottom()
    await focusComposer()
  }
}

/** Cancels the browser request; the backend persists any partial assistant output. */
const stopGeneration = () => {
  abortController?.abort()
  void focusComposer()
}

/** Resets only browser state; existing persisted conversations remain in history. */
const newConversation = () => {
  abortController?.abort()
  messages.value = []
  conversationId.value = null
  prompt.value = ''
  nextTick(resizeComposer)
  usage.value = { prompt: 0, completion: 0, total: 0 }
  thinkingEnabled.value = false
  followLatest.value = true
  attachments.value = []
  selectedKnowledgeBaseIds.value = []
}

/** Implements Enter-to-send while preserving Shift+Enter for multiline input. */
const handleComposerKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void sendPrompt()
  }
}

// Initial data is loaded only for the workspace route; the login route stays dependency-light.
onMounted(async () => {
  if (forwardOAuthCallback()) return
  void loadPlatformSettings().then(settings => {
    platformSettings.value = settings
    document.title = settings.platformName
  }).catch(() => { /* Keep the initial login page available if settings are unavailable. */ })
  try {
    const response = await fetch('/api/auth/options')
    if (response.ok) authOptions.value = await response.json()
  } catch {
    authOptions.value = { devLoginEnabled: false }
  }
  if (!isUserApp.value) return
  try {
    const response = await fetch('/api/auth/me')
    if (response.ok) currentUser.value = await response.json()
  } finally {
    userSessionChecked.value = true
  }
  if (currentUser.value && isWorkspace.value) {
    await Promise.all([loadModels(), loadConversations(), loadPromptTemplates(), loadKnowledgeBases(), loadAnnouncement()])
  }
})
</script>

<template>
  <AdminDashboard v-if="isAdmin" />
  <KnowledgeBasePage v-else-if="isKnowledge && currentUser" />
  <TeamPage v-else-if="isTeam && currentUser" />
  <ApiPlatformPage v-else-if="isApiPlatform && currentUser" />
  <!-- Authentication entry screen. -->
  <main v-else-if="!isUserApp || (userSessionChecked && !currentUser)" class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <img class="login-logo" src="/brand/sitp-logo.jpg" alt="SITP" />
      <p class="product-name">{{ platformSettings.platformName }}</p>
      <h1 id="login-title">统一身份认证</h1>
      <p class="login-copy">{{ platformSettings.loginDescription }}</p>
      <button class="primary-button login-button" type="button" @click="login">
        <ShieldCheck :size="18" />
        统一登录
      </button>
      <div v-if="authOptions.devLoginEnabled" class="dev-login-actions">
        <button class="secondary-button login-button" type="button" @click="devLogin('DEV001')">
          DEV001 团队管理员
        </button>
        <button class="secondary-button login-button" type="button" @click="devLogin('DEV002')">
          DEV002 普通成员
        </button>
      </div>
      <p class="login-note">账号与权限由统一身份认证中心管理</p>
      <p v-if="platformSettings.adminContact" class="login-note">联系管理员：{{ platformSettings.adminContact }}</p>
    </section>
  </main>

  <!-- Main application shell: navigation, conversation viewport, and composer. -->
  <main v-else-if="currentUser" class="workspace-shell">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="关闭侧栏" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <!-- Product identity and primary navigation. -->
      <div class="sidebar-header">
        <div class="compact-brand">
          <img class="sidebar-logo" src="/brand/sitp-logo.jpg" alt="SITP" />
          <span>{{ platformSettings.platformName }}</span>
        </div>
        <button class="icon-button mobile-only" title="关闭侧栏" @click="sidebarOpen = false"><PanelLeftClose :size="19" /></button>
      </div>

      <button class="new-chat" type="button" @click="newConversation">
        <MessageSquarePlus :size="18" />
        新建对话
      </button>

      <nav class="main-nav" aria-label="主导航">
        <a class="nav-item active" href="/app"><Bot :size="18" />对话</a>
        <a class="nav-item" href="/app/knowledge"><Library :size="18" />知识库</a>
        <a class="nav-item" href="/app/team"><Users :size="18" />团队空间</a>
        <a class="nav-item" href="/app/api-platform"><Terminal :size="18" />API 开发平台</a>
        <a class="nav-item" href="#"><Archive :size="18" />智能应用</a>
        <hr class="nav-divider" />
        <FeedbackPanel />
      </nav>

      <div class="history-section">
        <!-- Persisted conversations support search, grouping, selection, rename, and soft deletion. -->
        <div class="section-label">
          <span>最近</span>
          <button
            class="icon-button"
            type="button"
            title="搜索对话"
            @click="toggleHistorySearch"
          >
            <Search :size="16" />
          </button>
        </div>
        <div v-if="historySearchOpen" class="history-search">
          <Search :size="14" />
          <input
            ref="historySearchInput"
            v-model="historySearch"
            type="search"
            placeholder="搜索对话"
            aria-label="搜索对话"
            @keydown.esc="toggleHistorySearch"
          />
        </div>
        <template v-for="group in conversationGroups" :key="group.label">
          <div class="history-group-label">{{ group.label }}</div>
          <div
            v-for="conversation in group.conversations"
            :key="conversation.id"
            class="history-row"
            :class="{ 'active-history': conversation.id === conversationId }"
            @click="openConversation(conversation)"
          >
            <input
              v-if="editingConversationId === conversation.id"
              v-model="editingTitle"
              class="history-rename-input"
              maxlength="60"
              autofocus
              @click.stop
              @keydown.enter.prevent="saveRename"
              @keydown.esc.prevent="cancelRename"
              @blur="saveRename"
            />
            <template v-else>
              <button
                class="history-item"
                type="button"
                :title="conversation.preview"
                @click.stop="openConversation(conversation)"
              >
                {{ conversation.title || '未命名对话' }}
              </button>
              <div class="history-actions">
                <button type="button" title="重命名" @click.stop="startRename(conversation)">
                  <Pencil :size="13" />
                </button>
                <button type="button" title="删除" @click.stop="deleteConversation(conversation)">
                  <Trash2 :size="13" />
                </button>
              </div>
            </template>
          </div>
        </template>
        <p v-if="!conversationGroups.length" class="history-empty">
          {{ historySearch ? '未找到匹配对话' : '暂无对话' }}
        </p>
      </div>

      <div class="sidebar-footer">
        <div class="user-row">
          <div class="avatar">{{ currentUserInitial }}</div>
          <div class="user-meta">
            <strong>{{ currentUser?.displayName || '已登录用户' }}</strong>
            <span>{{ currentUserSubtitle }}</span>
          </div>
        </div>
      </div>
    </aside>

    <section class="chat-workspace">
      <!-- Model selection and service health. -->
      <header class="topbar">
        <button class="icon-button mobile-only" title="打开菜单" @click="sidebarOpen = true"><Menu :size="20" /></button>
        <div class="topbar-context-controls">
          <div class="model-selector">
          <ElSelect
            v-model="selectedModel"
            class="model-select"
            :disabled="isGenerating"
            popper-class="model-select-dropdown"
            aria-label="选择模型"
          >
            <template #prefix><Bot :size="16" /></template>
            <ElOption v-if="!models.length" label="DeepSeek Chat" value="deepseek-chat" />
            <ElOption
              v-for="model in models"
              :key="model.key"
              :label="model.display_name"
              :value="model.key"
            >
              <div class="model-option">
                <span class="model-option-icon"><Bot :size="15" /></span>
                <span>{{ model.display_name }}</span>
                <small>{{ model.capabilities.includes('vision') ? '多模态' : '对话' }}</small>
              </div>
            </ElOption>
          </ElSelect>
          </div>
          <ElDropdown v-if="!conversationId" trigger="click" @command="selectPromptTemplate">
            <button class="topbar-prompt-trigger" type="button" title="选择提示词模板">
              <span class="prompt-pill-label">提示词：</span><strong>{{ selectedTemplateName }}</strong><ChevronDown :size="14" />
            </button>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem command=""><span class="prompt-option"><strong>默认提示词</strong><small>通用</small></span></ElDropdownItem>
                <ElDropdownItem v-for="template in promptTemplates" :key="template.id" :command="template.id">
                  <span class="prompt-option"><strong>{{ template.name }}</strong><ElTag :type="template.scope === 'system' ? 'info' : 'success'" effect="plain" size="small">{{ template.scope === 'system' ? '系统' : '个人' }}</ElTag></span>
                </ElDropdownItem>
                <ElDropdownItem divided command="__manage__"><span class="prompt-manage-entry"><Settings :size="14" />管理提示词模板</span></ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
          <button v-else class="topbar-prompt-trigger locked" type="button" title="当前会话提示词已锁定" disabled>
            <span class="prompt-pill-label">提示词：</span><strong>{{ selectedTemplateName }}</strong><LockKeyhole :size="13" />
          </button>
        </div>
        <div class="topbar-actions">
          <span class="service-status" :class="{ offline: !serviceOnline }"><span></span>{{ serviceOnline ? '服务正常' : '服务离线' }}</span>
          <span v-if="usage.total" class="token-usage">{{ usage.total }} tokens</span>
          <span v-if="currentUser" class="current-user-name">{{ currentUser.displayName }}</span>
          <button v-if="currentUser" class="icon-button" type="button" title="退出登录" @click="logout"><LogOut :size="16" /></button>
        </div>
      </header>

      <div
        ref="conversationArea"
        class="conversation-area"
        :class="{ 'has-messages': messages.length }"
        @scroll="handleConversationScroll"
        @wheel.passive="handleConversationWheel"
        @touchstart.passive="pauseFollowing"
        @pointerdown="pauseFollowing"
        @keydown="handleConversationKeydown"
        @mouseup="handleConversationSelection"
        @click="handleConversationClick"
      >
        <!-- Empty-state suggestions are real prompts, not decorative cards. -->
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

        <div v-else ref="messageList" class="message-list">
          <!-- Assistant Markdown is sanitized; user text is rendered as plain text. -->
          <article
            v-for="(message, index) in messages"
            :key="index"
            class="message"
            :class="[message.role, { editing: editingMessageKey === messageDisplayKey(message, index) }]"
          >
            <div v-if="message.role === 'assistant'" class="message-avatar"><Bot :size="17" /></div>
            <div class="message-column">
              <div class="message-body" :class="{ error: message.error }">
                <div v-if="message.attachments?.length" class="message-attachments">
                  <button v-for="file in message.attachments" :key="file.id" class="message-attachment" type="button" title="点击下载文件" @click="downloadAttachment(file)">
                    <span class="attachment-file-icon"><FileText :size="17" /></span>
                    <span class="attachment-file-copy"><strong :title="file.originalName">{{ file.originalName }}</strong><small>{{ file.mediaType || '文件' }}</small></span>
                    <ElTag :type="attachmentTagType(file.status)" effect="light" size="small" round>{{ attachmentStatusLabel(file.status) }}</ElTag>
                    <Download class="attachment-download-icon" :size="15" />
                  </button>
                </div>
                <div v-if="message.role === 'assistant' && message.reasoningContent" class="reasoning-panel">
                  <button type="button" class="reasoning-toggle" @click="message.reasoningExpanded = !message.reasoningExpanded">
                    <ChevronDown v-if="message.reasoningExpanded" :size="14" />
                    <ChevronRight v-else :size="14" />
                    <span>{{ formatReasoningDuration(message.reasoningDurationMs) }}</span>
                  </button>
                  <div v-if="message.reasoningExpanded" class="reasoning-content">{{ message.reasoningContent }}</div>
                </div>
                <div v-if="message.content && message.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(message.content)"></div>
                <div v-else-if="message.content && editingMessageKey === messageDisplayKey(message, index)" class="user-message-editor">
                  <textarea
                    v-model="editingMessageContent"
                    rows="1"
                    @input="resizeMessageEditor"
                    @keydown.esc.prevent="cancelMessageEdit"
                  ></textarea>
                  <div>
                    <button type="button" @click="cancelMessageEdit">取消</button>
                    <button class="primary" type="button" :disabled="!editingMessageContent.trim() || isGenerating" @click="sendEditedMessage(message, index)">发送</button>
                  </div>
                </div>
                <div v-else-if="message.content" class="user-message-text">
                  <div v-if="message.quotedText" class="user-message-quote">
                    <Quote :size="14" />
                    <span>{{ message.quotedText }}</span>
                  </div>
                  <p :class="{ collapsed: shouldCollapseUserMessage(message) && !isUserMessageExpanded(message, index) }">{{ message.content }}</p>
                  <button
                    v-if="shouldCollapseUserMessage(message)"
                    class="user-message-expand"
                    type="button"
                    @click="toggleUserMessageExpanded(message, index)"
                  >
                    {{ isUserMessageExpanded(message, index) ? '收起' : '展开全部' }}
                  </button>
                </div>
                <div v-else class="typing-state" :aria-label="message.statusText || '模型正在生成'">
                  <span v-if="message.statusText">{{ message.statusText }}</span>
                  <span class="typing-indicator"><i></i><i></i><i></i></span>
                </div>
                <details v-if="message.role === 'assistant' && message.citations?.length" class="message-citations">
                  <summary><Library :size="14" />引用来源（{{ message.citations.length }}）</summary>
                  <button v-for="(citation, citationIndex) in message.citations" :key="citation.chunkId || citationIndex" type="button" @click="downloadCitation(citation)">
                    <span>[{{ citation.number || citationIndex + 1 }}] {{ citation.filename }}<small v-if="citation.pageNumber">第 {{ citation.pageNumber }} 页</small></span>
                    <p>{{ citation.content }}</p>
                    <Download :size="14" />
                  </button>
                </details>
              </div>
              <div v-if="message.content" class="message-actions">
                <button
                  v-if="message.role === 'assistant' || message.role === 'user'"
                  class="message-action"
                  type="button"
                  :title="message.role === 'user' ? '复制消息' : '复制回答'"
                  @click="copyMessage(message.content, index)"
                >
                  <Check v-if="copiedMessageIndex === index" :size="15" />
                  <Copy v-else :size="15" />
                </button>
                <button v-if="message.role === 'assistant' && message.id" class="message-action" type="button" title="重新生成" @click="regenerateMessage(message, index)"><RotateCcw :size="15" /></button>
                <button v-if="message.role === 'user' && message.id" class="message-action" type="button" title="编辑消息" @click="editMessage(message, index)"><Pencil :size="15" /></button>
                <button
                  v-if="message.role === 'assistant' && message.id"
                  class="message-action"
                  type="button"
                  title="从此处分支"
                  :disabled="branchingMessageIds.has(message.id) || branchedMessageIds.has(message.id)"
                  @click="branchConversation(message)"
                ><GitBranch :size="15" /></button>
                <span v-if="message.role === 'user' && message.id && (message.versionCount ?? 0) > 1" class="message-version-switch">
                  <button
                    type="button"
                    title="上一版本"
                    :disabled="switchingMessageVersionIds.has(message.id) || (message.versionIndex ?? 1) <= 1"
                    @click="activateMessageVersion(message, -1)"
                  ><ChevronLeft :size="15" /></button>
                  <b>{{ message.versionIndex ?? 1 }}/{{ message.versionCount }}</b>
                  <button
                    type="button"
                    title="下一版本"
                    :disabled="switchingMessageVersionIds.has(message.id) || (message.versionIndex ?? 1) >= (message.versionCount ?? 1)"
                    @click="activateMessageVersion(message, 1)"
                  ><ChevronRight :size="15" /></button>
                </span>
                <small v-if="message.editedAt">已编辑</small>
              </div>
            </div>
          </article>
        </div>
        <div
          v-if="selectionPopover"
          class="selection-popover"
          :style="{ left: `${selectionPopover.x}px`, top: `${selectionPopover.y}px` }"
        >
          <button type="button" @click="quoteSelectedText"><Quote :size="14" />引用提问</button>
        </div>
        <div v-if="messages.length" class="jump-to-latest-anchor">
          <button v-if="!atLatestReply" class="jump-to-latest" type="button" @click="jumpToLatest"><ArrowDown :size="15" />回到最新回复</button>
        </div>
      </div>

      <footer class="composer-wrap">
        <!-- The composer owns file/voice placeholders, cancellation, and keyboard submission. -->
        <div class="composer">
          <div v-if="attachments.length" class="attachment-list">
            <div v-for="file in attachments" :key="file.id" class="composer-attachment">
              <ElTag
                :type="attachmentTagType(file.status)"
                effect="plain"
                closable
                round
                @close="attachments = attachments.filter((item) => item.id !== file.id)"
              ><Paperclip :size="12" /><span :title="file.originalName">{{ file.originalName }}</span><small>{{ attachmentStatusLabel(file.status) }}</small></ElTag>
              <ElProgress v-if="file.status === 'uploaded' || file.status === 'processing'" :percentage="100" :show-text="false" :stroke-width="2" status="warning" :indeterminate="true" :duration="1.5" />
            </div>
          </div>
          <div v-if="recordingVoice || transcribingVoice" class="voice-recorder-panel">
            <div class="voice-recorder-status">
              <span class="voice-pulse"><Mic :size="18" /></span>
              <div>
                <strong>{{ transcribingVoice ? '正在识别语音' : '正在录音' }}</strong>
                <small>{{ transcribingVoice ? '请稍候，正在调用内网语音模型' : voiceDurationLabel }}</small>
              </div>
            </div>
            <div v-if="recordingVoice" class="voice-bars" aria-hidden="true">
              <span v-for="index in 18" :key="index"></span>
            </div>
            <div v-if="recordingVoice" class="voice-recorder-actions">
              <button class="voice-cancel-button" type="button" title="取消录音" :disabled="transcribingVoice" @click="cancelVoiceInput"><X :size="18" /></button>
              <button class="voice-confirm-button" type="button" title="完成录音并识别" :disabled="transcribingVoice" @click="finishVoiceInput"><Check :size="19" /></button>
            </div>
          </div>
          <template v-else>
            <div v-if="quotedSelection" class="composer-quote">
              <Quote :size="15" />
              <span>{{ quotedSelection.text }}</span>
              <button type="button" title="移除引用" @click="clearQuotedSelection"><X :size="14" /></button>
            </div>
            <textarea
              ref="promptInput"
              v-model="prompt"
              rows="1"
              placeholder="给智能工作台发送消息"
              :disabled="isGenerating"
              @focus="resizeComposer"
              @input="resizeComposer"
              @keydown="handleComposerKeydown"
            ></textarea>
          </template>
          <div v-if="!recordingVoice && !transcribingVoice" class="composer-toolbar">
            <div class="input-tools">
              <input ref="fileInput" class="file-input" type="file" multiple :accept="platformSettings.allowedExtensions" @change="uploadFiles" />
              <button class="icon-button" type="button" title="上传文件或图片" :disabled="uploadingFile" @click="fileInput?.click()"><Paperclip :size="19" /></button>
              <ElDropdown trigger="click" placement="top-start" @command="selectKnowledgeBase">
                <button class="knowledge-button" :class="{ active: selectedKnowledgeBaseNames }" type="button" :title="selectedKnowledgeBaseNames || '选择知识库'">
                  <Library :size="16" /><span>{{ selectedKnowledgeBaseNames || '知识库' }}</span><LockKeyhole v-if="conversationId" :size="12" /><ChevronDown v-else :size="12" />
                </button>
                <template #dropdown>
                  <ElDropdownMenu>
                    <ElDropdownItem v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" :command="knowledgeBase.id" :disabled="Boolean(conversationId)">
                      <span class="knowledge-chat-option"><Check v-if="selectedKnowledgeBaseIds.includes(knowledgeBase.id)" :size="13" /><span v-else></span>{{ knowledgeBase.name }}</span>
                    </ElDropdownItem>
                    <ElDropdownItem v-if="selectedKnowledgeBaseIds.length && !conversationId" divided command="__clear__">清除选择</ElDropdownItem>
                    <ElDropdownItem v-if="!knowledgeBases.length" disabled>暂无可用知识库</ElDropdownItem>
                  </ElDropdownMenu>
                </template>
              </ElDropdown>
            </div>
            <div class="composer-actions">
              <ElDropdown v-if="selectedModelSupportsThinking" trigger="click" placement="top-end" @command="selectThinkingMode">
                <button class="thinking-menu-trigger" :class="{ active: thinkingEnabled }" type="button" title="选择回答模式">
                  <span>{{ thinkingEnabled ? '深度思考' : '快速回答' }}</span><ChevronDown :size="13" />
                </button>
                <template #dropdown>
                  <ElDropdownMenu>
                    <ElDropdownItem command="fast" :class="{ selected: !thinkingEnabled }"><span class="thinking-option"><span><strong>快速回答</strong><small>直接生成答案</small></span></span></ElDropdownItem>
                    <ElDropdownItem command="thinking" :class="{ selected: thinkingEnabled }"><span class="thinking-option"><span><strong>深度思考</strong><small>启用模型推理</small></span></span></ElDropdownItem>
                  </ElDropdownMenu>
                </template>
              </ElDropdown>
              <button class="icon-button voice-button" type="button" title="语音输入" :disabled="isGenerating || transcribingVoice" @click="startVoiceInput"><Mic :size="19" /></button>
              <button v-if="isGenerating" class="send-button stop-button" type="button" title="停止生成" @click="stopGeneration"><Square :size="15" /></button>
              <button v-else class="send-button" type="button" title="发送" :disabled="!prompt.trim() || attachments.some((file) => file.status !== 'ready')" @click="sendPrompt()"><ArrowUp :size="18" /></button>
            </div>
          </div>
        </div>
        <p class="composer-note">{{ recordingVoice ? '点击对号完成识别，点击叉号取消本次录音' : transcribingVoice ? '正在使用内网语音模型识别...' : '内容由 AI 生成，请核实重要信息' }}</p>
      </footer>

      <div v-if="activeAnnouncement" class="announcement-modal-backdrop" @click.self="dismissAnnouncement">
        <section class="announcement-modal" role="dialog" aria-modal="true" aria-labelledby="announcement-title">
          <header>
            <div>
              <div class="announcement-release-meta">
                <strong>{{ announcementVersion(activeAnnouncement) }}</strong>
                <time>{{ announcementDate(activeAnnouncement) }}</time>
              </div>
              <h2 id="announcement-title">{{ activeAnnouncement.title }}</h2>
            </div>
            <button type="button" title="关闭公告" @click="dismissAnnouncement"><X :size="18" /></button>
          </header>
          <div v-if="!announcementHistoryOpen" class="announcement-content">
            <b>更新内容</b>
            <small v-if="announcementDate(activeAnnouncement)">最新公告 · 发布于 {{ announcementDate(activeAnnouncement) }}</small>
            <p>{{ activeAnnouncement.body }}</p>
          </div>
          <div v-else class="announcement-history">
            <button
              v-for="item in announcementHistory"
              :key="item.id"
              type="button"
              :class="{ active: item.id === activeAnnouncement.id }"
              @click="selectAnnouncement(item)"
            >
              <em>{{ announcementVersion(item) }} · {{ announcementDate(item) }}</em>
              <span>{{ announcementDate(item) }}</span>
              <strong>{{ item.title }}</strong>
              <p>{{ item.body }}</p>
            </button>
          </div>
          <footer>
            <button
              v-if="announcementHistory.length > 1"
              class="secondary"
              type="button"
              @click="announcementHistoryOpen = !announcementHistoryOpen"
            >
              {{ announcementHistoryOpen ? '返回最新公告' : '查看历史更新' }}
            </button>
            <button type="button" @click="dismissAnnouncement">我知道了</button>
          </footer>
        </section>
      </div>

      <div v-if="templateManagerOpen" class="template-manager-backdrop" @click.self="templateManagerOpen = false">
        <section class="template-manager">
          <header><div><h2>提示词模板</h2><p>系统模板只读；个人模板仅你自己可见和修改。</p></div><button type="button" @click="templateManagerOpen = false">×</button></header>
          <div class="template-manager-body">
            <div class="template-catalog">
              <button type="button" class="new-template" @click="editPromptTemplate()">＋ 新建个人模板</button>
              <article v-for="template in promptTemplates" :key="template.id">
                <div><strong>{{ template.name }}</strong><span>{{ template.scope === 'system' ? '系统' : '个人' }}</span></div>
                <p>{{ template.description || '暂无说明' }}</p>
                <div v-if="template.scope === 'personal'"><button type="button" @click="editPromptTemplate(template)">编辑</button><button type="button" @click="deletePromptTemplate(template)">删除</button></div>
              </article>
            </div>
            <form class="template-form" @submit.prevent="savePromptTemplate">
              <h3>{{ editingTemplateId ? '编辑个人模板' : '新建个人模板' }}</h3>
              <label>名称<input v-model="templateForm.name" maxlength="128" required /></label>
              <label>说明<input v-model="templateForm.description" maxlength="512" /></label>
              <label>系统提示词<textarea v-model="templateForm.systemPrompt" rows="10" maxlength="100000" required></textarea></label>
              <button type="submit">保存模板</button>
            </form>
          </div>
        </section>
      </div>
    </section>
  </main>
</template>
