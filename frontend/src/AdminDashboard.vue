<script setup lang="ts">
import ApiActivityPanel from './ApiActivityPanel.vue'
import FeedbackPanel from './FeedbackPanel.vue'
import TeamAdministratorRequests from './TeamAdministratorRequests.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  Activity,
  Bot,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  RefreshCw,
  Settings,
  ShieldCheck,
  Users,
  Zap,
} from 'lucide-vue-next'
import { ElDatePicker, ElOption, ElSelect } from 'element-plus'
import { adminPageMeta, resolveAdminPage } from './adminRouting'
import { groupUsage, sumUsage } from './usageStats'

interface AdminOverview {
  userCount: number
  conversationCount: number
  todayRequestCount: number
  todayTokenCount: number
}

interface DailyUsage {
  date: string
  conversationCount: number
  requestCount: number
  inputTokens: number
  outputTokens: number
}

interface UsageTotals {
  source: string
  requestCount: number
  inputTokens: number
  outputTokens: number
  failedRequestCount: number
}
interface UsageReport {
  month: string
  totals: UsageTotals[]
  selectedMonth: UsageTotals[]
  months: Array<Omit<UsageTotals, 'failedRequestCount'> & { month: string }>
  models: Array<UsageTotals & { modelKey: string }>
}

interface AdminUser {
  id: number
  accountNumber: string
  userNumber?: string | null
  displayName: string
  status: number
  administrator: boolean
  teamAdministrator: boolean
  lastLoginAt: string | null
  conversationCount: number
  totalTokens: number
  apiTotalQuota: number
  apiAvailableTokens: number
  apiReservedTokens: number
  apiQuotaUnlimited: boolean
  apiUsedTokens: number
  apiRequestCount: number
  apiKeyCount: number
}

interface AdminPrincipal {
  id: number
  username: string
  displayName: string
  source: 'local' | 'oauth2'
}

interface ModelRegistration {
  key: string
  display_name: string
  provider: string
  engine: 'vllm' | 'sglang' | 'ollama' | 'llama.cpp' | 'openai-compatible'
  category: 'language' | 'multimodal' | 'embedding' | 'rerank' | 'speech'
  base_url: string
  model_name: string
  capabilities: string[]
  enabled: boolean
  frontend_visible: boolean
  display_order?: number
  reasoning_mode?: 'none' | 'qwen3' | 'deepseek_v4' | 'deepseek_r1' | 'always'
  context_window?: number | string
  api_key_env?: string
  api_key?: string
}

interface ModelSettings {
  embeddingModel: string
  rerankerModel: string
  speechModel: string
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

interface AdminApiOverview {
  enabledKeyCount: number
  enabledModelCount: number
  todayRequestCount: number
  todayTokenCount: number
  totalRequestCount: number
  totalTokenCount: number
}

interface AdminApiModel {
  key: string
  displayName: string
  category: string
  capabilities: string[]
  contextWindow?: number
  apiEnabled: boolean
  available: boolean
}

interface AdminApiKey {
  id: string
  userId: number
  accountNumber: string
  displayName: string
  name: string
  maskedKey: string
  enabled: boolean
  createdAt: string
  lastUsedAt?: string | null
}

interface PromptTemplate {
  id: string
  name: string
  description: string
  systemPrompt: string
  scope: string
  enabled: boolean
}

const overview = ref<AdminOverview>({
  userCount: 0,
  conversationCount: 0,
  todayRequestCount: 0,
  todayTokenCount: 0,
})
const dailyUsage = ref<DailyUsage[]>([])
const apiDailyUsage = ref<DailyUsage[]>([])
const cumulativeUsage = ref<UsageTotals[]>([])
const usageMonth = ref(new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 7))
const usageModelScope = ref('month')
const usageReport = ref<UsageReport | null>(null)
const usageMonthLabel = computed(() => {
  const [year, month] = (usageReport.value?.month || usageMonth.value).split('-')
  return `${year}年${Number(month)}月`
})
const usageSources = [{ key: 'chat', label: '网页对话' }, { key: 'api', label: 'API 调用' }, { key: 'all', label: '合计' }]
const usageFor = (rows: UsageTotals[], source: string): UsageTotals => source === 'all'
  ? { source, ...sumUsage(rows.filter(row => row.source === 'chat' || row.source === 'api')) }
  : rows.find((row) => row.source === source)
  ?? { source, requestCount: 0, inputTokens: 0, outputTokens: 0, failedRequestCount: 0 }
const monthlyCombined = computed(() => groupUsage(usageReport.value?.months ?? [], row => row.month))
const modelUsageTotal = computed(() => sumUsage(usageReport.value?.models ?? []))
const dailyCombined = computed(() => groupUsage([
  ...dailyUsage.value.map(row => ({ ...row, source: 'chat' })),
  ...apiDailyUsage.value.map(row => ({ ...row, source: 'api' })),
], row => row.date))
const dailyCombinedTotals = computed(() => ({
  chat: sumUsage(dailyCombined.value.map(row => row.chat)),
  api: sumUsage(dailyCombined.value.map(row => row.api)),
  total: sumUsage(dailyCombined.value.map(row => row.total)),
}))
const cumulativeRequests = computed(() => cumulativeUsage.value.reduce((sum, row) => sum + row.requestCount, 0))
const cumulativeTokens = computed(() => cumulativeUsage.value.reduce((sum, row) => sum + row.inputTokens + row.outputTokens, 0))
const users = ref<AdminUser[]>([])
const userSearch = ref('')
const userPage = ref(0)
const userPageSize = ref(20)
const userTotal = ref(0)
const models = ref<ModelRegistration[]>([])
const orderedModels = computed(() => [...models.value].sort((a, b) => (a.display_order ?? 100) - (b.display_order ?? 100)))
const apiOverview = ref<AdminApiOverview>({
  enabledKeyCount: 0,
  enabledModelCount: 0,
  todayRequestCount: 0,
  todayTokenCount: 0,
  totalRequestCount: 0,
  totalTokenCount: 0,
})
const apiModels = ref<AdminApiModel[]>([])
const apiKeys = ref<AdminApiKey[]>([])
const apiManagementError = ref('')
const announcements = ref<PlatformAnnouncement[]>([])
const systemTemplates = ref<PromptTemplate[]>([])
const loading = ref(true)
const loadError = ref(false)
const sessionChecked = ref(false)
const currentAdmin = ref<AdminPrincipal | null>(null)
const loginUsername = ref('')
const loginPassword = ref('')
const loginError = ref('')
const loginSubmitting = ref(false)
const modelEditorOpen = ref(false)
const modelSaving = ref(false)
const modelSaveError = ref('')
const modelHealth = ref<Record<string, string>>({})
const discoveredModels = ref<string[]>([])
const discoveringModels = ref(false)
const modelDiscoverMessage = ref('')
const modelSettings = ref<ModelSettings>({ embeddingModel: '', rerankerModel: '', speechModel: '' })
const modelSettingsSaving = ref(false)
const modelSettingsMessage = ref('')
const announcementSaving = ref(false)
const announcementMessage = ref('')
const announcementForm = ref({ id: '', version: '', title: '', body: '', published: true })
const announcementEditorOpen = ref(false)
const announcementDetail = ref<PlatformAnnouncement | null>(null)
const quotaEditorUser = ref<AdminUser | null>(null)
const quotaAmount = ref(10_000_000)
const quotaOperation = ref<'add' | 'deduct' | 'unlimited' | 'limited'>('add')
const quotaNote = ref('')
const quotaOperationId = ref('')
const quotaHistory = ref<Array<{id: number; actor: string; operation: string; amount: number; note: string; created_at: string}>>([])
const quotaSaving = ref(false)
const quotaSaveError = ref('')
const modelForm = ref<ModelRegistration>({
  key: '', display_name: '', provider: 'openai-compatible', engine: 'openai-compatible', category: 'language', base_url: '', model_name: '',
  capabilities: ['chat'], enabled: true, frontend_visible: true, reasoning_mode: 'none', context_window: 32768, api_key: '',
})
const templateForm = ref({ id: '', name: '', description: '', systemPrompt: '', enabled: true })
const ADMIN_IDLE_TIMEOUT_MS = 60 * 60 * 1000
let adminIdleTimer: number | null = null

const contextWindowOptions = [
  { label: '8K', value: 8192 },
  { label: '16K', value: 16384 },
  { label: '32K', value: 32768 },
  { label: '64K', value: 65536 },
  { label: '128K', value: 131072 },
  { label: '256K', value: 262144 },
  { label: '1M', value: 1048576 },
]

const adminPage = computed(() => resolveAdminPage(window.location.pathname))
const pageMeta = computed(() => adminPageMeta[adminPage.value])

const maxDailyTokens = computed(() => {
  return Math.max(...dailyUsage.value.map((day) => day.inputTokens + day.outputTokens), 1)
})
const maxApiDailyTokens = computed(() => {
  return Math.max(...apiDailyUsage.value.map((day) => day.inputTokens + day.outputTokens), 1)
})
const totalUserPages = computed(() => Math.max(1, Math.ceil(userTotal.value / userPageSize.value)))
const embeddingModels = computed(() => models.value.filter((model) => model.enabled && model.category === 'embedding'))
const rerankerModels = computed(() => models.value.filter((model) => model.enabled && model.category === 'rerank'))
const speechModels = computed(() => models.value.filter((model) => model.enabled && model.category === 'speech'))
const missingEmbeddingModel = computed(() => modelSettings.value.embeddingModel
  && !embeddingModels.value.some((model) => model.key === modelSettings.value.embeddingModel))
const missingRerankerModel = computed(() => modelSettings.value.rerankerModel
  && !rerankerModels.value.some((model) => model.key === modelSettings.value.rerankerModel))
const missingSpeechModel = computed(() => modelSettings.value.speechModel
  && !speechModels.value.some((model) => model.key === modelSettings.value.speechModel))
const adminSidebarName = computed(() => currentAdmin.value?.username || 'admin')
const publishedAnnouncements = computed(() => announcements.value.filter((item) => item.published))

const formatNumber = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const parseContextWindow = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isFinite(value) && value > 0 ? Math.floor(value) : null
  const normalized = value.trim().toLowerCase()
  const match = normalized.match(/^(\d+(?:\.\d+)?)\s*([km])?$/)
  if (!match) return null
  const amount = Number(match[1])
  const multiplier = match[2] === 'm' ? 1024 * 1024 : match[2] === 'k' ? 1024 : 1
  const parsed = Math.round(amount * multiplier)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}
const formatContextWindow = (value?: number | string | null) => {
  const parsed = parseContextWindow(value)
  if (!parsed) return '-'
  if (parsed % 1048576 === 0) return `${parsed / 1048576}M`
  if (parsed % 1024 === 0) return `${parsed / 1024}K`
  return formatNumber(parsed)
}
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric',
  day: 'numeric',
}).format(new Date(`${value}T00:00:00`))
const formatDateTime = (value: string | null) => value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '尚未登录'

const loadUsers = async () => {
  const params = new URLSearchParams({
    page: String(userPage.value),
    size: String(userPageSize.value),
  })
  const query = userSearch.value.trim()
  if (query) params.set('q', query)
  const response = await fetch(`/api/admin/users?${params.toString()}`)
  if (!response.ok) throw new Error('Users unavailable')
  const payload = await response.json()
  users.value = payload.users ?? []
  userTotal.value = payload.total ?? users.value.length
  userPage.value = payload.page ?? userPage.value
  userPageSize.value = payload.size ?? userPageSize.value
}

/** Loads only the API data required by the current administration route. */
const loadCurrentPage = async () => {
  loading.value = true
  loadError.value = false
  try {
    if (adminPage.value === 'overview') {
      const [response, apiResponse, totalsResponse] = await Promise.all([
        fetch('/api/admin/overview'),
        fetch('/api/admin/api/overview'),
        fetch('/api/admin/usage/totals'),
      ])
      if (!response.ok) throw new Error('Overview unavailable')
      overview.value = await response.json()
      if (!totalsResponse.ok) throw new Error('Totals unavailable')
      cumulativeUsage.value = await totalsResponse.json()
      if (apiResponse.ok) apiOverview.value = await apiResponse.json()
    } else if (adminPage.value === 'users') {
      await loadUsers()
    } else if (adminPage.value === 'usage') {
      const [response, apiResponse, reportResponse] = await Promise.all([
        fetch('/api/admin/usage/daily?days=7'),
        fetch('/api/admin/api/usage/daily?days=7'),
        fetch(`/api/admin/usage/report?month=${encodeURIComponent(usageMonth.value)}&scope=${usageModelScope.value}`),
      ])
      if (!response.ok || !apiResponse.ok || !reportResponse.ok) throw new Error('Usage unavailable')
      dailyUsage.value = await response.json()
      apiDailyUsage.value = await apiResponse.json()
      usageReport.value = await reportResponse.json()
    } else if (adminPage.value === 'models') {
      await loadModels()
    } else if (adminPage.value === 'api') {
      apiManagementError.value = ''
      const [overviewResponse, modelsResponse, keysResponse] = await Promise.all([
        fetch('/api/admin/api/overview'),
        fetch('/api/admin/api/models'),
        fetch('/api/admin/api/keys'),
      ])
      if (!overviewResponse.ok || !modelsResponse.ok || !keysResponse.ok) {
        throw new Error('API management unavailable')
      }
      apiOverview.value = await overviewResponse.json()
      apiModels.value = await modelsResponse.json()
      apiKeys.value = await keysResponse.json()
      if (!apiModels.value.length) {
        apiManagementError.value = '暂未获取到模型列表，请确认 AI 服务已启动，并且模型管理中已添加模型。'
      }
    } else if (adminPage.value === 'prompts') {
      await loadSystemTemplates()
    } else if (adminPage.value === 'announcements') {
      await loadAnnouncements()
    }
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** Loads administrative model registrations without making dashboard metrics depend on AI uptime. */
const loadModels = async () => {
  try {
    const [modelsResponse, settingsResponse] = await Promise.all([
      fetch('/api/admin/models'),
      fetch('/api/admin/model-settings'),
    ])
    if (modelsResponse.status === 401 || settingsResponse.status === 401) {
      currentAdmin.value = null
      return
    }
    models.value = modelsResponse.ok ? await modelsResponse.json() : []
    if (settingsResponse.ok) modelSettings.value = await settingsResponse.json()
  } catch {
    models.value = []
  }
}

/** Loads historical user-facing product announcements for the dedicated admin module. */
const loadAnnouncements = async () => {
  try {
    const response = await fetch('/api/admin/announcements')
    if (response.status === 401) {
      currentAdmin.value = null
      return
    }
    announcements.value = response.ok ? await response.json() : []
  } catch {
    announcements.value = []
    announcementMessage.value = '公告列表暂时不可用'
  }
}

const createAnnouncement = () => {
  announcementForm.value = { id: '', version: '', title: '', body: '', published: true }
  announcementMessage.value = ''
  announcementEditorOpen.value = true
}

const editAnnouncement = (item: PlatformAnnouncement) => {
  announcementDetail.value = item
  announcementMessage.value = ''
}

/** Creates or updates one announcement while preserving the historical list. */
const saveAnnouncement = async () => {
  announcementSaving.value = true
  announcementMessage.value = ''
  try {
    const createResponse = await fetch('/api/admin/announcements', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        version: announcementForm.value.version,
        title: announcementForm.value.title,
        body: announcementForm.value.body,
        published: true,
      }),
    })
    if (createResponse.status === 401) {
      currentAdmin.value = null
      announcementMessage.value = '登录状态已过期，请重新登录后台后再保存。'
      return
    }
    if (createResponse.status === 403) {
      announcementMessage.value = '当前账号没有保存公告的权限，请确认已使用管理员账号登录。'
      return
    }
    if (!createResponse.ok) {
      const detail = await createResponse.text()
      throw new Error(detail || `HTTP ${createResponse.status}`)
    }
    const created: PlatformAnnouncement = await createResponse.json()
    await loadAnnouncements()
    announcementDetail.value = created
    announcementEditorOpen.value = false
    announcementMessage.value = '公告已发布，用户将看到一次弹窗'
  } catch (error) {
    announcementMessage.value = `保存失败：${error instanceof Error ? error.message : '后台服务不可用'}`
  } finally {
    announcementSaving.value = false
  }
}

/** Persists platform default embedding and rerank model choices in the business database. */
const saveModelSettings = async () => {
  modelSettingsSaving.value = true
  modelSettingsMessage.value = ''
  try {
    const response = await fetch('/api/admin/model-settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(modelSettings.value),
    })
    if (!response.ok) {
      const detail = await response.text()
      throw new Error(detail || `HTTP ${response.status}`)
    }
    modelSettings.value = await response.json()
    modelSettingsMessage.value = '默认模型已保存'
  } catch (error) {
    modelSettingsMessage.value = `保存失败：${error instanceof Error ? error.message : '后台服务不可用'}`
  } finally {
    modelSettingsSaving.value = false
  }
}

/** Loads system prompt templates, including disabled records visible only to administrators. */
const loadSystemTemplates = async () => {
  const response = await fetch('/api/admin/prompt-templates')
  systemTemplates.value = response.ok ? await response.json() : []
}

const clearAdminIdleTimer = () => {
  if (adminIdleTimer !== null) {
    window.clearTimeout(adminIdleTimer)
    adminIdleTimer = null
  }
}

const logout = async () => {
  clearAdminIdleTimer()
  await fetch('/api/admin/auth/logout', { method: 'POST' })
  currentAdmin.value = null
  users.value = []
}

const resetAdminIdleTimer = () => {
  if (!currentAdmin.value) return
  clearAdminIdleTimer()
  adminIdleTimer = window.setTimeout(() => {
    void logout()
  }, ADMIN_IDLE_TIMEOUT_MS)
}

const handleAdminActivity = () => {
  resetAdminIdleTimer()
}

const checkSession = async () => {
  try {
    const response = await fetch('/api/admin/auth/me')
    if (response.ok) {
      currentAdmin.value = await response.json()
      resetAdminIdleTimer()
      await loadCurrentPage()
    }
  } finally {
    sessionChecked.value = true
  }
}

const login = async () => {
  loginSubmitting.value = true
  loginError.value = ''
  try {
    const response = await fetch('/api/admin/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: loginUsername.value, password: loginPassword.value }),
    })
    if (!response.ok) {
      loginError.value = '用户名或密码错误'
      return
    }
    currentAdmin.value = await response.json()
    loginPassword.value = ''
    resetAdminIdleTimer()
    await loadCurrentPage()
  } catch {
    loginError.value = '无法连接管理服务'
  } finally {
    loginSubmitting.value = false
  }
}

const setAdministrator = async (user: AdminUser) => {
  const enabled = !user.administrator
  const response = await fetch(`/api/admin/users/${user.id}/administrator`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  if (response.ok) user.administrator = enabled
}

/** Grants team creation without granting system-administration access. */
const setTeamAdministrator = async (user: AdminUser) => {
  const enabled = !user.teamAdministrator
  const response = await fetch(`/api/admin/users/${user.id}/team-administrator`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  if (response.ok) user.teamAdministrator = enabled
}

/** Opens a clean registration editor for an OpenAI-compatible inference endpoint. */
const createModel = () => {
  modelForm.value = {
    key: '', display_name: '', provider: 'openai-compatible', engine: 'openai-compatible', category: 'language', base_url: '', model_name: '',
    capabilities: ['chat'], enabled: true, frontend_visible: true, display_order: 100, reasoning_mode: 'none', context_window: 32768, api_key: '',
  }
  modelSaveError.value = ''
  modelDiscoverMessage.value = ''
  discoveredModels.value = []
  modelEditorOpen.value = true
}

/** Copies an existing registration so edits remain cancelable until explicitly saved. */
const editModel = (model: ModelRegistration) => {
  modelForm.value = { ...JSON.parse(JSON.stringify(model)), display_order: model.display_order ?? 100, reasoning_mode: model.reasoning_mode ?? 'none', api_key: '' }
  modelSaveError.value = ''
  modelDiscoverMessage.value = ''
  discoveredModels.value = []
  modelEditorOpen.value = true
}

const friendlyDiscoverError = async (response: Response) => {
  const detail = await response.text().catch(() => '')
  const lower = detail.toLowerCase()
  if (response.status === 401 || response.status === 403 || lower.includes('unauthorized')
    || lower.includes('forbidden') || lower.includes('api key') || lower.includes('401')
    || lower.includes('403')) {
    return '模型识别失败：API 密钥无效或没有权限，请检查 API Key。'
  }
  if (response.status === 400 || lower.includes('base_url')) {
    return 'API 地址格式不正确，请填写完整地址，例如 http://127.0.0.1:8000/v1'
  }
  if (response.status === 404) return '模型识别失败：未找到模型列表接口，请确认 API 地址是否为 OpenAI 兼容地址。'
  if (response.status === 408 || response.status === 504 || lower.includes('timeout')) {
    return '模型识别超时：请确认模型服务已启动，或稍后重试。'
  }
  if (response.status >= 500) return '模型识别失败：模型服务暂时不可用，请查看服务日志。'
  return '模型识别失败：请检查 API 地址、密钥和模型服务状态。'
}

/** Discovers model identifiers using the entered API address and write-only credential. */
const discoverModels = async () => {
  modelDiscoverMessage.value = ''
  discoveredModels.value = []
  if (!modelForm.value.base_url) {
    modelDiscoverMessage.value = '请先填写 API 地址。'
    return
  }
  discoveringModels.value = true
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 20_000)
  try {
    const response = await fetch('/api/admin/models/discover', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ base_url: modelForm.value.base_url, api_key: modelForm.value.api_key || null }),
      signal: controller.signal,
    })
    if (!response.ok) {
      modelDiscoverMessage.value = await friendlyDiscoverError(response)
      return
    }
    const result = await response.json().catch(() => null)
    if (!Array.isArray(result)) {
      modelDiscoverMessage.value = '模型识别失败：服务返回格式不正确。'
      return
    }
    discoveredModels.value = result
    if (!modelForm.value.model_name && discoveredModels.value.length) {
      modelForm.value.model_name = discoveredModels.value[0]
    }
    modelDiscoverMessage.value = discoveredModels.value.length
      ? `发现 ${discoveredModels.value.length} 个模型`
      : '没有发现模型，请确认该 API 已启用模型列表接口。'
  } catch (error) {
    modelDiscoverMessage.value = error instanceof DOMException && error.name === 'AbortError'
      ? '模型识别超时：请确认模型服务地址可访问。'
      : '模型识别失败：无法连接模型服务，请确认地址、端口和网络可达。'
  } finally {
    window.clearTimeout(timeout)
    discoveringModels.value = false
  }
}

const friendlyModelSaveError = async (response: Response) => {
  const detail = await response.text()
  const lower = detail.toLowerCase()
  if (detail.includes('string_pattern_mismatch') || detail.includes('[a-z0-9][a-z0-9._-]+')
    || lower.includes('"key"')) {
    return '模型标识格式不正确：请使用小写字母、数字、点、下划线或短横线，并且必须以小写字母或数字开头。'
  }
  if (lower.includes('unauthorized') || lower.includes('forbidden') || lower.includes('api key')
    || lower.includes('401') || lower.includes('403')) {
    return '模型保存失败：API 密钥无效或没有权限，请检查 API Key。'
  }
  if (lower.includes('"category"') && lower.includes('"speech"') && lower.includes('enum')) {
    return '模型保存失败：当前 AI 服务还不支持“语音识别服务”类型，请重启或更新 AI 服务后再保存。'
  }
  if (detail.includes('url') || detail.includes('base_url')) {
    return 'API 地址格式不正确，请填写完整地址，例如 http://127.0.0.1:8000/v1'
  }
  if (lower.includes('model_name')) return '模型保存失败：请选择或填写模型名称。'
  if (lower.includes('display_name')) return '模型保存失败：请填写显示名称。'
  if (lower.includes('capabilities')) return '模型保存失败：模型能力配置不正确，请重新选择模型类型后保存。'
  if (response.status === 404) return '模型管理服务暂不可用，请确认后台和 AI 服务已启动。'
  if (response.status === 422) return `模型保存失败：表单内容校验未通过。${detail.slice(0, 180)}`
  if (response.status >= 500) return '模型保存失败，请确认后台服务和数据库可用。'
  return '模型保存失败，请检查表单内容后重试。'
}

/** Persists API connection, model capability, visibility, and concurrency settings atomically. */
const saveModel = async () => {
  modelSaving.value = true
  modelSaveError.value = ''
  try {
    if (!/^[a-z0-9][a-z0-9._-]+$/.test(modelForm.value.key)) {
      modelSaveError.value = '模型标识格式不正确：请使用小写字母、数字、点、下划线或短横线，并且必须以小写字母或数字开头。'
      return
    }
    if (!modelForm.value.display_name.trim()) {
      modelSaveError.value = '模型保存失败：请填写显示名称。'
      return
    }
    if (!modelForm.value.model_name.trim()) {
      modelSaveError.value = '模型保存失败：请选择或填写模型名称。'
      return
    }
    const contextWindow = parseContextWindow(modelForm.value.context_window)
    if (!contextWindow) {
      modelSaveError.value = '上下文窗口格式不正确：请选择 8K、32K、128K 等选项，或填写类似 96K、131072 的值。'
      return
    }
    modelForm.value.context_window = contextWindow
    modelForm.value.engine = 'openai-compatible'
    if (modelForm.value.category === 'embedding') {
      modelForm.value.capabilities = ['embedding']
      modelForm.value.frontend_visible = false
    } else if (modelForm.value.category === 'rerank') {
      modelForm.value.capabilities = ['rerank']
      modelForm.value.frontend_visible = false
    } else if (modelForm.value.category === 'speech') {
      modelForm.value.capabilities = ['speech_to_text']
      modelForm.value.frontend_visible = false
    } else {
      modelForm.value.capabilities = modelForm.value.category === 'multimodal'
        ? ['chat', 'vision'] : ['chat']
    }
    const response = await fetch(`/api/admin/models/${encodeURIComponent(modelForm.value.key)}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(modelForm.value),
    })
    if (response.ok) {
      modelEditorOpen.value = false
      await loadModels()
    } else {
      // Show validation/proxy failures so the save button never appears to do nothing.
      modelSaveError.value = await friendlyModelSaveError(response)
    }
  } catch (error) {
    modelSaveError.value = '保存失败：无法连接后台服务，请确认后台服务已启动。'
  } finally {
    modelSaving.value = false
  }
}

/** Removes only platform registration metadata after explicit confirmation. */
const deleteModel = async (model: ModelRegistration) => {
  if (!window.confirm(`确定移除模型注册“${model.display_name}”吗？模型文件不会被删除。`)) return
  const response = await fetch(`/api/admin/models/${encodeURIComponent(model.key)}`, { method: 'DELETE' })
  if (response.ok) await loadModels()
}

const setApiModelEnabled = async (model: AdminApiModel) => {
  const response = await fetch(`/api/admin/api/models/${encodeURIComponent(model.key)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !model.apiEnabled }),
  })
  if (response.ok) await loadCurrentPage()
}

const searchUsers = async () => {
  userPage.value = 0
  loading.value = true
  try {
    await loadUsers()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const changeUserPage = async (page: number) => {
  userPage.value = Math.max(0, Math.min(page, totalUserPages.value - 1))
  loading.value = true
  try {
    await loadUsers()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const openApiQuotaEditor = async (user: AdminUser) => {
  quotaEditorUser.value = user
  quotaAmount.value = 10_000_000
  quotaOperation.value = 'add'
  quotaNote.value = ''
  quotaOperationId.value = crypto.randomUUID()
  quotaHistory.value = []
  quotaSaveError.value = ''
  try {
    const response = await fetch(`/api/admin/api/users/${user.id}/credits`)
    if (!response.ok) throw new Error()
    const history = await response.json()
    if (quotaEditorUser.value?.id === user.id) quotaHistory.value = history
  } catch {
    if (quotaEditorUser.value?.id === user.id) quotaSaveError.value = '调整记录加载失败，请重新打开'
  }
}

const saveApiQuota = async () => {
  const user = quotaEditorUser.value
  if (!user) return
  const totalQuota = Number(quotaAmount.value)
  if (['add', 'deduct'].includes(quotaOperation.value)
      && (!Number.isSafeInteger(totalQuota) || totalQuota <= 0)) {
    quotaSaveError.value = '请输入大于 0 的整数 Token 数量'
    return
  }
  quotaSaving.value = true
  quotaSaveError.value = ''
  try {
    const response = await fetch(`/api/admin/api/users/${user.id}/credits`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        amount: ['add', 'deduct'].includes(quotaOperation.value) ? totalQuota : 0,
        operation: quotaOperation.value,
        note: quotaNote.value,
        operationId: quotaOperationId.value,
      }),
    })
    if (!response.ok) {
      quotaSaveError.value = quotaOperation.value === 'deduct' ? '扣减失败，请确认未超过当前可用余额' : '额度保存失败，请稍后重试'
      return
    }
    quotaEditorUser.value = null
    await loadCurrentPage()
  } catch {
    quotaSaveError.value = '无法连接管理服务'
  } finally {
    quotaSaving.value = false
  }
}

const setApiKeyEnabled = async (key: AdminApiKey) => {
  const response = await fetch(`/api/admin/api/keys/${key.id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !key.enabled }),
  })
  if (response.ok) await loadCurrentPage()
}

/** Executes a bounded readiness probe and keeps the latest result beside its model. */
const checkModel = async (model: ModelRegistration) => {
  modelHealth.value[model.key] = '检查中'
  try {
    const response = await fetch(`/api/admin/models/${encodeURIComponent(model.key)}/health`, { method: 'POST' })
    const result = response.ok ? await response.json() : null
    modelHealth.value[model.key] = result ? `${result.status} · ${result.latency_ms}ms` : '不可用'
  } catch {
    modelHealth.value[model.key] = '不可用'
  }
}

/** Loads one system template into the administrator editor or clears it for creation. */
const editSystemTemplate = (template?: PromptTemplate) => {
  templateForm.value = template
    ? { id: template.id, name: template.name, description: template.description, systemPrompt: template.systemPrompt, enabled: template.enabled }
    : { id: '', name: '', description: '', systemPrompt: '', enabled: true }
}

/** Creates or updates platform-owned templates without exposing this capability to normal users. */
const saveSystemTemplate = async () => {
  const id = templateForm.value.id
  const response = await fetch(id ? `/api/admin/prompt-templates/${id}` : '/api/admin/prompt-templates', {
    method: id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(templateForm.value),
  })
  if (response.ok) {
    editSystemTemplate()
    await loadSystemTemplates()
  }
}

const adminActivityEvents = ['click', 'keydown', 'mousemove', 'scroll', 'touchstart'] as const

onMounted(() => {
  adminActivityEvents.forEach((event) => window.addEventListener(event, handleAdminActivity, { passive: true }))
  void checkSession()
})

onUnmounted(() => {
  adminActivityEvents.forEach((event) => window.removeEventListener(event, handleAdminActivity))
  clearAdminIdleTimer()
})
</script>

<template>
  <main v-if="sessionChecked && !currentAdmin" class="admin-login-page">
    <section class="admin-login-card">
      <div class="admin-login-mark"><img src="/brand/sitp-logo.jpg" alt="SITP" /></div>
      <p class="admin-login-eyebrow">智能工作台</p>
      <h1>管理后台登录</h1>
      <p class="admin-login-copy">使用本地管理账号登录，与 OAuth2 业务账号相互独立。</p>
      <form @submit.prevent="login">
        <label>用户名<input v-model="loginUsername" autocomplete="username" required /></label>
        <label>密码<input v-model="loginPassword" type="password" autocomplete="current-password" required /></label>
        <p v-if="loginError" class="login-error">{{ loginError }}</p>
        <button type="submit" :disabled="loginSubmitting">
          <ShieldCheck :size="17" />{{ loginSubmitting ? '正在登录…' : '登录管理后台' }}
        </button>
      </form>
    </section>
  </main>

  <main v-else-if="currentAdmin" class="admin-shell">
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <img src="/brand/sitp-logo.jpg" alt="SITP" />
        <div><strong>智能工作台</strong><span>管理后台</span></div>
      </div>
      <nav aria-label="后台导航">
        <a :class="{ active: adminPage === 'overview' }" href="/admin"><LayoutDashboard :size="18" />概览</a>
        <a :class="{ active: adminPage === 'users' }" href="/admin/users"><Users :size="18" />用户管理</a>
        <a :class="{ active: adminPage === 'usage' }" href="/admin/usage"><Activity :size="18" />用量统计</a>
        <a :class="{ active: adminPage === 'models' }" href="/admin/models"><Bot :size="18" />模型管理</a>
        <a :class="{ active: adminPage === 'api' }" href="/admin/api"><Zap :size="18" />API 管理</a>
        <a :class="{ active: adminPage === 'prompts' }" href="/admin/prompts"><MessageSquare :size="18" />提示词模板</a>
        <a :class="{ active: adminPage === 'announcements' }" href="/admin/announcements"><MessageSquare :size="18" />公告管理</a>
        <a :class="{ active: adminPage === 'feedback' }" href="/admin/feedback"><MessageSquare :size="18" />意见反馈</a>
        <a :class="{ active: adminPage === 'settings' }" href="/admin/settings"><Settings :size="18" />系统设置</a>
      </nav>
      <div class="admin-sidebar-footer">
        <div><strong>{{ adminSidebarName }}</strong><span>本地管理员</span></div>
        <button type="button" title="退出登录" @click="logout"><LogOut :size="16" /></button>
      </div>
    </aside>

    <section class="admin-content">
      <header class="admin-topbar">
        <div><h1>{{ pageMeta[0] }}</h1><p>{{ pageMeta[1] }}</p></div>
        <button type="button" :disabled="loading" @click="loadCurrentPage">
          <RefreshCw :size="16" :class="{ spinning: loading }" />刷新
        </button>
      </header>

      <div v-if="loadError" class="admin-error">暂时无法加载后台数据，请确认核心服务和数据库已启动。</div>

      <div v-if="adminPage === 'overview'" class="metric-grid" aria-label="平台指标">
        <article><span class="metric-icon green"><Users :size="20" /></span><div><small>平台用户</small><strong>{{ formatNumber(overview.userCount) }}</strong></div></article>
        <article><span class="metric-icon blue"><MessageSquare :size="20" /></span><div><small>有效会话</small><strong>{{ formatNumber(overview.conversationCount) }}</strong></div></article>
        <article><span class="metric-icon orange"><Zap :size="20" /></span><div><small>今日请求（对话 + API）</small><strong>{{ formatNumber(overview.todayRequestCount + apiOverview.todayRequestCount) }}</strong></div></article>
        <article><span class="metric-icon purple"><Activity :size="20" /></span><div><small>今日 Token（对话 + API）</small><strong>{{ formatNumber(overview.todayTokenCount + apiOverview.todayTokenCount) }}</strong></div></article>
      </div>

      <section v-if="adminPage === 'overview'" class="admin-panel api-overview-panel">
        <div class="panel-heading"><div><h2>累计统计</h2></div></div>
        <div class="metric-grid compact">
          <article><div><small>累计请求（对话 + API）</small><strong>{{ formatNumber(cumulativeRequests) }}</strong></div></article>
          <article><div><small>累计 Token（对话 + API）</small><strong>{{ formatNumber(cumulativeTokens) }}</strong></div></article>
          <article><div><small>对话累计 Token</small><strong>{{ formatNumber(usageFor(cumulativeUsage, 'chat').inputTokens + usageFor(cumulativeUsage, 'chat').outputTokens) }}</strong></div></article>
          <article><div><small>API 累计 Token</small><strong>{{ formatNumber(usageFor(cumulativeUsage, 'api').inputTokens + usageFor(cumulativeUsage, 'api').outputTokens) }}</strong></div></article>
        </div>
      </section>

      <section v-if="adminPage === 'overview'" class="admin-panel api-overview-panel">
        <div class="panel-heading">
          <div><h2>API 中转站</h2><p>独立统计开放 API 的 Key、模型与调用量，不计入网页对话用量</p></div>
          <a class="panel-link" href="/admin/api">进入 API 管理</a>
        </div>
        <div class="metric-grid compact">
          <article><span class="metric-icon green"><Zap :size="20" /></span><div><small>启用 Key</small><strong>{{ formatNumber(apiOverview.enabledKeyCount) }}</strong></div></article>
          <article><span class="metric-icon blue"><Bot :size="20" /></span><div><small>开放模型</small><strong>{{ formatNumber(apiOverview.enabledModelCount) }}</strong></div></article>
          <article><span class="metric-icon orange"><Activity :size="20" /></span><div><small>今日 API 请求</small><strong>{{ formatNumber(apiOverview.todayRequestCount) }}</strong></div></article>
          <article><span class="metric-icon purple"><MessageSquare :size="20" /></span><div><small>今日 API Token</small><strong>{{ formatNumber(apiOverview.todayTokenCount) }}</strong></div></article>
        </div>
      </section>

      <section v-if="adminPage === 'models'" class="admin-panel model-panel">
        <div class="panel-heading">
          <div><h2>模型管理</h2><p>动态注册 OpenAI 兼容端点、模型能力与前台可见性</p></div>
          <button class="panel-action" type="button" @click="createModel">新增模型</button>
        </div>
        <form class="model-defaults" @submit.prevent="saveModelSettings">
          <label>默认 Embedding 模型
            <ElSelect v-model="modelSettings.embeddingModel" class="admin-select" popper-class="admin-select-dropdown">
              <ElOption label="不启用向量检索" value="" />
              <ElOption v-if="missingEmbeddingModel" :label="`当前配置不可用：${modelSettings.embeddingModel}`" :value="modelSettings.embeddingModel" />
              <ElOption v-for="model in embeddingModels" :key="model.key" :label="model.display_name" :value="model.key" />
            </ElSelect>
          </label>
          <label>默认 Reranker 模型
            <ElSelect v-model="modelSettings.rerankerModel" class="admin-select" popper-class="admin-select-dropdown">
              <ElOption label="暂不重排" value="" />
              <ElOption v-if="missingRerankerModel" :label="`当前配置不可用：${modelSettings.rerankerModel}`" :value="modelSettings.rerankerModel" />
              <ElOption v-for="model in rerankerModels" :key="model.key" :label="model.display_name" :value="model.key" />
            </ElSelect>
          </label>
          <label>默认语音识别模型
            <ElSelect v-model="modelSettings.speechModel" class="admin-select" popper-class="admin-select-dropdown">
              <ElOption label="不启用语音识别" value="" />
              <ElOption v-if="missingSpeechModel" :label="`当前配置不可用：${modelSettings.speechModel}`" :value="modelSettings.speechModel" />
              <ElOption v-for="model in speechModels" :key="model.key" :label="model.display_name" :value="model.key" />
            </ElSelect>
          </label>
          <button type="submit" :disabled="modelSettingsSaving">{{ modelSettingsSaving ? '保存中…' : '保存默认模型' }}</button>
          <span v-if="modelSettingsMessage">{{ modelSettingsMessage }}</span>
        </form>
        <div class="model-grid">
          <article v-for="model in orderedModels" :key="model.key" class="model-card">
            <div class="model-card-heading"><span><Bot :size="18" /></span><div><strong>{{ model.display_name }}</strong><small>{{ model.key }}</small></div><i :class="{ enabled: model.enabled }">{{ model.enabled ? '启用' : '停用' }}</i></div>
            <dl><div><dt>类型</dt><dd>{{ ({ language: '大语言模型', multimodal: '多模态', embedding: 'Embedding', rerank: 'Rerank', speech: '语音识别' } as Record<string, string>)[model.category] }}</dd></div><div><dt>运行</dt><dd>{{ model.enabled ? '启用' : '停用' }}</dd></div><div><dt>前台展示</dt><dd>{{ model.frontend_visible ? '展示' : '隐藏' }}</dd></div><div><dt>上下文</dt><dd>{{ formatContextWindow(model.context_window) }}</dd></div></dl>
            <p>{{ model.base_url }}</p>
            <p v-if="model.capabilities.includes('chat')">前台展示顺序：{{ model.display_order ?? 100 }}</p>
            <div class="model-card-actions"><button type="button" @click="checkModel(model)">{{ modelHealth[model.key] || '健康检查' }}</button><button type="button" @click="editModel(model)">编辑</button><button class="danger" type="button" @click="deleteModel(model)">移除</button></div>
          </article>
          <p v-if="!models.length" class="model-empty">AI 服务尚无模型注册或当前不可用。</p>
        </div>
      </section>

      <section v-if="adminPage === 'api'" class="admin-panel api-admin-panel">
        <div class="panel-heading">
          <div><h2>API 管理</h2><p>统一 API 的模型开放与 Key 状态管理</p></div>
          <a class="panel-link" href="/app/api-platform">查看前台 API 开发平台</a>
        </div>

        <div class="metric-grid compact">
          <article><span class="metric-icon green"><Zap :size="20" /></span><div><small>启用 Key</small><strong>{{ formatNumber(apiOverview.enabledKeyCount) }}</strong></div></article>
          <article><span class="metric-icon blue"><Bot :size="20" /></span><div><small>开放模型</small><strong>{{ formatNumber(apiOverview.enabledModelCount) }}</strong></div></article>
          <article><span class="metric-icon orange"><Activity :size="20" /></span><div><small>累计 API 请求</small><strong>{{ formatNumber(apiOverview.totalRequestCount) }}</strong></div></article>
          <article><span class="metric-icon purple"><MessageSquare :size="20" /></span><div><small>累计 API Token</small><strong>{{ formatNumber(apiOverview.totalTokenCount) }}</strong></div></article>
        </div>
        <p v-if="apiManagementError" class="api-admin-notice">{{ apiManagementError }}</p>

        <ApiActivityPanel mode="admin" initial-tab="management">
        <template #management>
        <div class="api-admin-section">
          <div class="panel-heading slim"><div><h2>开放模型</h2><p>API 中转站可调用模型独立控制，不受前台对话展示开关影响</p></div></div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>模型</th><th>类型</th><th>能力</th><th>上下文</th><th>API 开放</th></tr></thead>
              <tbody>
                <tr v-for="model in apiModels" :key="model.key">
                  <td><strong>{{ model.displayName }}</strong><br /><small>{{ model.key }}</small></td>
                  <td>{{ model.available ? model.category : '当前不可用' }}</td>
                  <td>{{ model.capabilities.join(', ') || '-' }}</td>
                  <td>{{ formatContextWindow(model.contextWindow) }}</td>
                  <td><button class="admin-toggle" :class="{ enabled: model.apiEnabled }" type="button" @click="setApiModelEnabled(model)"><span></span>{{ model.apiEnabled ? (model.available ? '已开放' : '已开放但不可调用') : '未开放' }}</button></td>
                </tr>
                <tr v-if="!apiModels.length && !loading"><td colspan="5" class="empty-table">暂无可配置模型</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="api-admin-section">
          <div class="panel-heading slim"><div><h2>API Keys</h2><p>管理员只能查看脱敏 Key，可禁用异常 Key，不能查看完整密钥</p></div></div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>用户</th><th>Key 名称</th><th>脱敏 Key</th><th>状态</th><th>创建时间</th><th>最近使用</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="key in apiKeys" :key="key.id">
                  <td><strong>{{ key.displayName }}</strong><br /><small>{{ key.accountNumber }}</small></td>
                  <td>{{ key.name }}</td>
                  <td><code>{{ key.maskedKey }}</code></td>
                  <td><span class="status-pill" :class="{ inactive: !key.enabled }">{{ key.enabled ? '启用' : '禁用' }}</span></td>
                  <td>{{ formatDateTime(key.createdAt) }}</td>
                  <td>{{ formatDateTime(key.lastUsedAt || null) }}</td>
                  <td><button class="admin-toggle" :class="{ enabled: key.enabled }" type="button" @click="setApiKeyEnabled(key)"><span></span>{{ key.enabled ? '禁用' : '启用' }}</button></td>
                </tr>
                <tr v-if="!apiKeys.length && !loading"><td colspan="7" class="empty-table">暂无 API Key</td></tr>
              </tbody>
            </table>
          </div>
        </div>
        </template>
        </ApiActivityPanel>
      </section>

        <FeedbackPanel v-if="adminPage === 'feedback'" admin />
        <section v-if="adminPage === 'prompts'" class="admin-panel prompt-admin-panel">
        <div class="panel-heading"><div><h2>系统提示词模板</h2><p>所有用户可选择，只有后台管理员可以修改</p></div><button class="panel-action" type="button" @click="editSystemTemplate()">新增模板</button></div>
        <div class="prompt-admin-layout">
          <div class="prompt-admin-list">
            <article v-for="template in systemTemplates" :key="template.id" @click="editSystemTemplate(template)"><div><strong>{{ template.name }}</strong><span :class="{ off: !template.enabled }">{{ template.enabled ? '启用' : '停用' }}</span></div><p>{{ template.description || '暂无说明' }}</p></article>
          </div>
          <form class="prompt-admin-form" @submit.prevent="saveSystemTemplate">
            <label>名称<input v-model="templateForm.name" maxlength="128" required /></label>
            <label>说明<input v-model="templateForm.description" maxlength="512" /></label>
            <label>系统提示词<textarea v-model="templateForm.systemPrompt" rows="6" maxlength="100000" required></textarea></label>
            <label class="prompt-enabled"><input v-model="templateForm.enabled" type="checkbox" />启用模板</label>
            <button type="submit">{{ templateForm.id ? '保存修改' : '创建模板' }}</button>
          </form>
        </div>
      </section>

      <section v-if="adminPage === 'announcements'" class="admin-panel announcement-panel">
        <div class="panel-heading">
          <div><h2>公告管理</h2><p>发布新模型、新功能或维护通知，前台用户每个发布版本只弹窗一次</p></div>
          <button class="panel-action" type="button" @click="createAnnouncement">新增公告</button>
        </div>
        <div class="announcement-layout">
          <div class="announcement-list">
            <article
              v-for="item in publishedAnnouncements"
              :key="item.id"
              :class="{ active: item.id === announcementDetail?.id, locked: item.published }"
              :title="item.published ? '已发布公告暂不支持编辑，请新增一条公告' : '编辑草稿公告'"
              @click="editAnnouncement(item)"
            >
              <div><strong>{{ item.version }} · {{ item.title }}</strong><span :class="{ off: !item.published }">{{ item.published ? '已发布' : '草稿' }}</span></div>
              <p>{{ item.body }}</p>
              <small>{{ item.publishedAt ? `发布：${formatDateTime(item.publishedAt)}` : `更新：${formatDateTime(item.updatedAt)}` }}</small>
            </article>
            <p v-if="!publishedAnnouncements.length" class="announcement-empty">暂无历史公告</p>
          </div>
        </div>
      </section>

      <div v-if="announcementEditorOpen" class="admin-modal-backdrop">
        <form class="admin-modal announcement-editor-modal" @submit.prevent="saveAnnouncement">
          <div class="panel-heading">
            <div><h2>新增公告</h2><p>公告保存后会立即发布，并作为一个新的版本记录。</p></div>
            <button class="editor-close" type="button" @click="announcementEditorOpen = false">×</button>
          </div>
          <label>版本号<input v-model="announcementForm.version" maxlength="64" placeholder="例如：V1.2.0 或 2026.06" required /></label>
          <label>公告标题<input v-model="announcementForm.title" maxlength="120" placeholder="例如：新模型已上线" required /></label>
          <label>更新内容<textarea v-model="announcementForm.body" maxlength="5000" rows="9" placeholder="说明本次更新内容、影响范围或使用入口" required></textarea></label>
          <div class="announcement-actions fixed">
            <span v-if="announcementMessage">{{ announcementMessage }}</span>
            <button type="button" @click="announcementEditorOpen = false">取消</button>
            <button class="primary" type="submit" :disabled="announcementSaving">{{ announcementSaving ? '发布中…' : '发布公告' }}</button>
          </div>
        </form>
      </div>

      <div v-if="announcementDetail" class="admin-modal-backdrop" @click.self="announcementDetail = null">
        <section class="admin-modal announcement-detail-modal">
          <div class="panel-heading">
            <div><h2>{{ announcementDetail.version }} · {{ announcementDetail.title }}</h2><p>{{ announcementDetail.publishedAt ? `发布：${formatDateTime(announcementDetail.publishedAt)}` : `更新：${formatDateTime(announcementDetail.updatedAt)}` }}</p></div>
            <button class="editor-close" type="button" @click="announcementDetail = null">×</button>
          </div>
          <p>{{ announcementDetail.body }}</p>
        </section>
      </div>

      <div v-if="adminPage === 'models' && modelEditorOpen" class="model-editor-backdrop">
        <form class="model-editor" @submit.prevent="saveModel">
          <div class="panel-heading"><div><h2>{{ models.some((item) => item.key === modelForm.key) ? '编辑模型' : '接入模型 API' }}</h2><p>填写 OpenAI 兼容 API 地址和密钥后识别模型</p></div><button class="editor-close" type="button" @click="modelEditorOpen = false">×</button></div>
          <div class="model-form-grid">
            <label>模型标识<input v-model="modelForm.key" pattern="[a-z0-9][a-z0-9._-]+" required /></label>
            <label>显示名称<input v-model="modelForm.display_name" required /></label>
            <label v-if="modelForm.category === 'language' || modelForm.category === 'multimodal'">前台展示顺序（数值越小越靠前）<input v-model.number="modelForm.display_order" type="number" min="0" max="999999" step="1" required /></label>
            <label>模型类型
              <ElSelect v-model="modelForm.category" class="admin-select" popper-class="admin-select-dropdown">
                <ElOption label="大语言模型" value="language" />
                <ElOption label="多模态模型" value="multimodal" />
                <ElOption label="Embedding 服务" value="embedding" />
                <ElOption label="Rerank 服务" value="rerank" />
                <ElOption label="语音识别服务" value="speech" />
              </ElSelect>
            </label>
            <label>上下文窗口
              <ElSelect v-model="modelForm.context_window" class="admin-select" popper-class="admin-select-dropdown" filterable allow-create default-first-option>
                <ElOption v-for="option in contextWindowOptions" :key="option.value" :label="option.label" :value="option.value" />
              </ElSelect>
            </label>
            <label>推理模式
              <ElSelect v-model="modelForm.reasoning_mode" class="admin-select" popper-class="admin-select-dropdown">
                <ElOption label="不支持思考" value="none" />
                <ElOption label="Qwen3 enable_thinking" value="qwen3" />
                <ElOption label="DeepSeek-V4 thinking_mode" value="deepseek_v4" />
                <ElOption label="DeepSeek-R1 reasoning_content" value="deepseek_r1" />
                <ElOption label="始终推理/仅展示推理内容" value="always" />
              </ElSelect>
            </label>
            <label class="wide">API 地址<input v-model="modelForm.base_url" type="url" placeholder="http://127.0.0.1:8000/v1" required /></label>
            <label class="wide">API Key<input v-model="modelForm.api_key" type="password" autocomplete="new-password" placeholder="编辑时留空表示保留原密钥" /></label>
            <div class="wide discovery-row">
              <button type="button" :disabled="discoveringModels" @click="discoverModels">{{ discoveringModels ? '识别中…' : '识别 API 中的模型' }}</button>
              <span v-if="modelDiscoverMessage" :class="{ error: !discoveredModels.length }">{{ modelDiscoverMessage }}</span>
            </div>
            <label class="wide">模型名称
              <ElSelect v-if="discoveredModels.length" v-model="modelForm.model_name" class="admin-select" popper-class="admin-select-dropdown">
                <ElOption v-for="name in discoveredModels" :key="name" :label="name" :value="name" />
              </ElSelect>
              <input v-else v-model="modelForm.model_name" placeholder="可手动填写模型 ID" required />
            </label>
            <label class="model-enabled"><input v-model="modelForm.enabled" type="checkbox" />启用 API 调用</label>
            <label class="model-enabled"><input v-model="modelForm.frontend_visible" type="checkbox" />在前台对话模型列表展示</label>
          </div>
          <p v-if="modelSaveError" class="model-save-error">{{ modelSaveError }}</p>
          <div class="editor-actions"><button type="button" @click="modelEditorOpen = false">取消</button><button class="primary" type="submit" :disabled="modelSaving">{{ modelSaving ? '保存中…' : '保存模型' }}</button></div>
        </form>
      </div>

      <section v-if="adminPage === 'usage'" class="admin-panel usage-panel">
        <div class="panel-heading usage-filters">
          <div><h2>月度与累计用量</h2><p>月度统计按北京时间，Token 为输入与输出之和；对话包含已记录的标题生成消耗。</p></div>
          <ElDatePicker v-model="usageMonth" type="month" value-format="YYYY-MM" format="YYYY 年 MM 月" :clearable="false" :disabled="loading" aria-label="统计月份" @change="loadCurrentPage" />
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>来源</th><th>{{ usageMonthLabel }}请求</th><th>{{ usageMonthLabel }}输入 Token</th><th>{{ usageMonthLabel }}输出 Token</th><th>{{ usageMonthLabel }}总 Token</th><th>累计请求</th><th>累计输入 Token</th><th>累计输出 Token</th><th>累计总 Token</th></tr></thead>
            <tbody><tr v-for="source in usageSources" :key="source.key" :class="{ 'usage-total-row': source.key === 'all' }">
              <td>{{ source.label }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.selectedMonth ?? [], source.key).requestCount) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.selectedMonth ?? [], source.key).inputTokens) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.selectedMonth ?? [], source.key).outputTokens) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.selectedMonth ?? [], source.key).inputTokens + usageFor(usageReport?.selectedMonth ?? [], source.key).outputTokens) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.totals ?? [], source.key).requestCount) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.totals ?? [], source.key).inputTokens) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.totals ?? [], source.key).outputTokens) }}</td>
              <td>{{ formatNumber(usageFor(usageReport?.totals ?? [], source.key).inputTokens + usageFor(usageReport?.totals ?? [], source.key).outputTokens) }}</td>
            </tr></tbody>
          </table>
        </div>
        <div class="panel-heading usage-stat-heading"><div><h2>月度明细</h2><p>{{ usageMonthLabel }}及之前 11 个月</p></div></div>
        <div class="table-wrap"><table>
          <thead><tr><th>月份</th><th>对话请求</th><th>API 请求</th><th>请求合计</th><th>对话 Token</th><th>API Token</th><th>合计输入 Token</th><th>合计输出 Token</th><th>Token 合计</th></tr></thead>
          <tbody><tr v-for="row in monthlyCombined" :key="row.key">
            <td>{{ row.key }}</td><td>{{ formatNumber(row.chat.requestCount) }}</td><td>{{ formatNumber(row.api.requestCount) }}</td><td>{{ formatNumber(row.total.requestCount) }}</td>
            <td>{{ formatNumber(row.chat.inputTokens + row.chat.outputTokens) }}</td><td>{{ formatNumber(row.api.inputTokens + row.api.outputTokens) }}</td>
            <td>{{ formatNumber(row.total.inputTokens) }}</td><td>{{ formatNumber(row.total.outputTokens) }}</td><td><strong>{{ formatNumber(row.total.inputTokens + row.total.outputTokens) }}</strong></td>
          </tr><tr v-if="!monthlyCombined.length"><td colspan="9" class="empty-table">暂无月度用量</td></tr></tbody>
        </table></div>
        <div class="panel-heading usage-stat-heading"><div><h2>分模型用量</h2></div>
          <ElSelect v-model="usageModelScope" :disabled="loading" aria-label="分模型统计范围" @change="loadCurrentPage">
            <ElOption :label="usageMonthLabel" value="month" /><ElOption label="历史累计" value="all" />
          </ElSelect>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>模型标识</th><th>来源</th><th>请求次数</th><th>失败次数</th><th>输入 Token</th><th>输出 Token</th><th>总 Token</th></tr></thead>
          <tbody><tr v-for="row in usageReport?.models ?? []" :key="`${row.source}-${row.modelKey}`">
            <td>{{ row.modelKey }}</td><td>{{ row.source === 'chat' ? '网页对话' : 'API 调用' }}</td><td>{{ formatNumber(row.requestCount) }}</td><td>{{ formatNumber(row.failedRequestCount) }}</td>
            <td>{{ formatNumber(row.inputTokens) }}</td><td>{{ formatNumber(row.outputTokens) }}</td><td>{{ formatNumber(row.inputTokens + row.outputTokens) }}</td>
          </tr><tr v-if="!usageReport?.models.length"><td colspan="7" class="empty-table">暂无模型用量</td></tr></tbody>
          <tfoot v-if="usageReport?.models.length"><tr class="usage-total-row"><td colspan="2">合计（当前范围）</td><td>{{ formatNumber(modelUsageTotal.requestCount) }}</td><td>{{ formatNumber(modelUsageTotal.failedRequestCount) }}</td><td>{{ formatNumber(modelUsageTotal.inputTokens) }}</td><td>{{ formatNumber(modelUsageTotal.outputTokens) }}</td><td>{{ formatNumber(modelUsageTotal.inputTokens + modelUsageTotal.outputTokens) }}</td></tr></tfoot>
        </table></div>
        <div class="usage-stat-heading"></div>
        <div class="panel-heading"><div><h2>近 7 天用量</h2><p>每日一行，展示对话、API 及合计用量</p></div></div>
        <div class="table-wrap"><table>
          <thead><tr><th>日期</th><th>对话请求</th><th>API 请求</th><th>请求合计</th><th>对话 Token</th><th>API Token</th><th>合计输入 Token</th><th>合计输出 Token</th><th>Token 合计</th></tr></thead>
          <tbody><tr v-for="row in dailyCombined" :key="row.key">
            <td>{{ row.key }}</td><td>{{ formatNumber(row.chat.requestCount) }}</td><td>{{ formatNumber(row.api.requestCount) }}</td><td>{{ formatNumber(row.total.requestCount) }}</td>
            <td>{{ formatNumber(row.chat.inputTokens + row.chat.outputTokens) }}</td><td>{{ formatNumber(row.api.inputTokens + row.api.outputTokens) }}</td>
            <td>{{ formatNumber(row.total.inputTokens) }}</td><td>{{ formatNumber(row.total.outputTokens) }}</td><td><strong>{{ formatNumber(row.total.inputTokens + row.total.outputTokens) }}</strong></td>
          </tr><tr v-if="!dailyCombined.length"><td colspan="9" class="empty-table">暂无每日用量</td></tr></tbody>
          <tfoot v-if="dailyCombined.length"><tr class="usage-total-row"><td>近 7 天合计</td>
            <td>{{ formatNumber(dailyCombinedTotals.chat.requestCount) }}</td><td>{{ formatNumber(dailyCombinedTotals.api.requestCount) }}</td><td>{{ formatNumber(dailyCombinedTotals.total.requestCount) }}</td>
            <td>{{ formatNumber(dailyCombinedTotals.chat.inputTokens + dailyCombinedTotals.chat.outputTokens) }}</td><td>{{ formatNumber(dailyCombinedTotals.api.inputTokens + dailyCombinedTotals.api.outputTokens) }}</td>
            <td>{{ formatNumber(dailyCombinedTotals.total.inputTokens) }}</td><td>{{ formatNumber(dailyCombinedTotals.total.outputTokens) }}</td><td>{{ formatNumber(dailyCombinedTotals.total.inputTokens + dailyCombinedTotals.total.outputTokens) }}</td>
          </tr></tfoot>
        </table></div>
        <div class="usage-split">
          <article>
            <div class="panel-heading slim"><div><h2>对话用量</h2><p>网页工作台对话请求</p></div></div>
            <div class="usage-chart" :class="{ loading }">
              <div v-for="day in dailyUsage" :key="day.date" class="usage-column">
                <div class="bar-area" :title="`${formatNumber(day.inputTokens + day.outputTokens)} Token`">
                  <div class="usage-bar" :style="{ height: `${Math.max(4, ((day.inputTokens + day.outputTokens) / maxDailyTokens) * 100)}%` }"></div>
                </div>
                <strong>{{ formatNumber(day.requestCount) }} 次</strong>
                <span>{{ formatDate(day.date) }}</span>
              </div>
              <p v-if="!dailyUsage.length && !loading" class="empty-chart">暂无对话用量数据</p>
            </div>
          </article>
          <article>
            <div class="panel-heading slim"><div><h2>API 用量</h2><p>API 中转站每日使用 Token 数</p></div></div>
            <div class="usage-chart" :class="{ loading }">
              <div v-for="day in apiDailyUsage" :key="day.date" class="usage-column">
                <div class="bar-area" :title="`${formatNumber(day.inputTokens + day.outputTokens)} Token`">
                  <div class="usage-bar api" :style="{ height: `${Math.max(4, ((day.inputTokens + day.outputTokens) / maxApiDailyTokens) * 100)}%` }"></div>
                </div>
                <strong>{{ formatNumber(day.inputTokens + day.outputTokens) }} Token</strong>
                <span>{{ formatDate(day.date) }}</span>
              </div>
              <p v-if="!apiDailyUsage.length && !loading" class="empty-chart">暂无 API 用量数据</p>
            </div>
          </article>
        </div>
      </section>

      <TeamAdministratorRequests v-if="adminPage === 'users'" @reviewed="loadUsers" />
      <section v-if="adminPage === 'users'" class="admin-panel user-panel">
        <div class="panel-heading"><div><h2>用户概况</h2><p>所有 OAuth2 影子用户、权限、对话用量与 API 额度</p></div><span>共 {{ formatNumber(userTotal) }} 位用户</span></div>
        <form class="user-search" @submit.prevent="searchUsers">
          <input v-model="userSearch" type="search" placeholder="搜索姓名、账号、工号、邮箱" />
          <button type="submit">搜索</button>
          <button type="button" @click="userSearch = ''; searchUsers()">清空</button>
        </form>
        <div class="table-wrap">
          <table>
            <thead><tr><th>用户</th><th>工号</th><th>状态</th><th>后台权限</th><th>团队权限</th><th>会话数</th><th>对话 Token</th><th>API 可用额度</th><th>API 累计已用</th><th>API 请求</th><th>Key 数</th><th>最近登录</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td><div class="table-user"><span>{{ user.displayName.slice(0, 1) }}</span><strong>{{ user.displayName }}</strong></div></td>
                <td>{{ user.userNumber || '-' }}</td>
                <td><span class="status-pill" :class="{ inactive: user.status !== 1 }">{{ user.status === 1 ? '正常' : '停用' }}</span></td>
                <td><button class="admin-toggle" :class="{ enabled: user.administrator }" type="button" @click="setAdministrator(user)"><span></span>{{ user.administrator ? '管理员' : '普通用户' }}</button></td>
                <td><button class="admin-toggle" :class="{ enabled: user.teamAdministrator }" type="button" @click="setTeamAdministrator(user)"><span></span>{{ user.teamAdministrator ? '团队管理员' : '普通成员' }}</button></td>
                <td>{{ formatNumber(user.conversationCount) }}</td>
                <td>{{ formatNumber(user.totalTokens) }}</td>
                <td>{{ user.apiQuotaUnlimited ? '无限制' : formatNumber(user.apiAvailableTokens) }}<small v-if="user.apiReservedTokens">（预占 {{ formatNumber(user.apiReservedTokens) }}）</small></td>
                <td>{{ formatNumber(user.apiUsedTokens) }}</td>
                <td>{{ formatNumber(user.apiRequestCount) }}</td>
                <td>{{ formatNumber(user.apiKeyCount) }}</td>
                <td>{{ formatDateTime(user.lastLoginAt) }}</td>
                <td><button class="table-action" type="button" @click="openApiQuotaEditor(user)">管理额度</button></td>
              </tr>
              <tr v-if="!users.length && !loading"><td colspan="13" class="empty-table">暂无用户数据</td></tr>
            </tbody>
          </table>
        </div>
        <div class="pagination-row">
          <button type="button" :disabled="userPage <= 0 || loading" @click="changeUserPage(userPage - 1)">上一页</button>
          <span>第 {{ userPage + 1 }} / {{ totalUserPages }} 页</span>
          <button type="button" :disabled="userPage + 1 >= totalUserPages || loading" @click="changeUserPage(userPage + 1)">下一页</button>
        </div>
      </section>

      <div v-if="quotaEditorUser" class="admin-modal-backdrop">
        <form class="admin-modal quota-editor-modal" @submit.prevent="saveApiQuota">
          <header>
            <div><h2>管理 API Token 额度</h2><p>{{ quotaEditorUser.displayName }} · 可用 {{ quotaEditorUser.apiQuotaUnlimited ? '无限制' : formatNumber(quotaEditorUser.apiAvailableTokens) }}</p></div>
            <button type="button" class="editor-close" title="关闭" :disabled="quotaSaving" @click="quotaEditorUser = null">×</button>
          </header>
          <ElSelect v-model="quotaOperation" aria-label="额度操作" :disabled="quotaSaving" style="width:100%;margin-top:20px">
            <ElOption label="追加额度" value="add" />
            <ElOption label="扣减额度" value="deduct" />
            <ElOption label="设为无限制" value="unlimited" />
            <ElOption label="恢复余额限制" value="limited" />
          </ElSelect>
          <label v-if="quotaOperation === 'add' || quotaOperation === 'deduct'">
            <span>{{ quotaOperation === 'add' ? '追加' : '扣减' }} Token 数量</span>
            <input v-model.number="quotaAmount" type="number" min="1" step="1" :disabled="quotaSaving" required />
          </label>
          <p v-else class="quota-unlimited-hint">{{ quotaOperation === 'unlimited' ? '持续记录用量，不受余额限制。' : '按现有余额恢复限制，历史已用量保留。' }}</p>
          <label><span>备注</span><input v-model="quotaNote" maxlength="500" :disabled="quotaSaving" /></label>
          <p v-if="quotaSaveError" class="model-save-error">{{ quotaSaveError }}</p>
          <footer class="editor-actions">
            <button type="button" :disabled="quotaSaving" @click="quotaEditorUser = null">取消</button>
            <button class="primary" type="submit" :disabled="quotaSaving">{{ quotaSaving ? '保存中…' : '保存' }}</button>
          </footer>
          <h3>最近调整记录</h3>
          <p v-if="!quotaHistory.length">暂无调整记录</p>
          <div v-for="item in quotaHistory" :key="item.id" class="quota-history-row">
            <strong>{{ ({add:'追加',deduct:'扣减',unlimited:'设为无限制',limited:'恢复余额限制'} as Record<string,string>)[item.operation] }} {{ item.amount ? formatNumber(item.amount) : '' }}</strong>
            <small>{{ item.actor }} · {{ formatDateTime(item.created_at) }}</small><p v-if="item.note">{{ item.note }}</p>
          </div>
        </form>
      </div>

      <section v-if="adminPage === 'settings'" class="admin-panel settings-panel">
        <div class="panel-heading"><div><h2>系统设置</h2><p>这里展示运行策略。域名、HTTP/HTTPS 端口由部署文件和反向代理固定管理。</p></div></div>
        <div class="settings-grid"><article><strong>访问地址</strong><span>由 Docker Compose、nginx 与证书配置决定，部署后不在后台动态修改</span></article><article><strong>认证模式</strong><span>本地管理员 + OAuth2 用户</span></article><article><strong>对象存储</strong><span>MinIO 私有 Bucket</span></article><article><strong>异步任务</strong><span>有界后台执行器</span></article><article><strong>模型默认值</strong><span>Embedding、Reranker、语音识别在模型管理中维护</span></article><article><strong>安全原则</strong><span>密钥不写入浏览器，部署参数不通过页面热修改</span></article></div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.usage-total-row { font-weight: 600; background: #f2f6f4; }
.usage-stat-heading { margin-top: 28px; }
.usage-filters { flex-wrap: wrap; gap: 14px; }
.usage-stat-heading > .el-select { width: 160px; }
.quota-history-row { display: grid; gap: 5px; padding: 12px 0; border-bottom: 1px solid #e4e8e6; overflow-wrap: anywhere; }
.quota-history-row small { color: #7a837f; }
.quota-history-row p { margin: 0; white-space: pre-wrap; }
.admin-login-page { min-height: 100vh; display: grid; place-items: center; padding: 24px; color: #29302d; background: radial-gradient(circle at 50% 0, #edf5f2, #f5f6f7 45%); }
.admin-login-card { width: min(100%, 390px); padding: 34px; text-align: center; background: #fff; border: 1px solid #e1e5e3; border-radius: 13px; box-shadow: 0 18px 50px rgba(28, 50, 42, .09); }.admin-login-mark { width: 150px; display: grid; place-items: center; margin: 0 auto 19px; }.admin-login-mark img { display: block; width: 100%; height: auto; }.admin-login-eyebrow { margin: 0 0 5px; color: #298166; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.admin-login-card h1 { margin: 0; font-size: 24px; }.admin-login-copy { margin: 10px 0 23px; color: #7b8380; font-size: 12px; line-height: 1.6; }.admin-login-card form { display: grid; gap: 14px; text-align: left; }.admin-login-card label { display: grid; gap: 6px; color: #555e5a; font-size: 11px; font-weight: 600; }.admin-login-card input { height: 39px; padding: 0 11px; color: #2e3431; background: #fff; border: 1px solid #d6dbd8; border-radius: 7px; outline: 0; }.admin-login-card input:focus { border-color: #4f8f7b; box-shadow: 0 0 0 3px #e8f2ef; }.admin-login-card form > button { height: 41px; display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 3px; color: #fff; background: #1d7159; border: 0; border-radius: 7px; font-weight: 600; }.admin-login-card form > button:hover:not(:disabled) { background: #155d48; }.admin-login-card > a { display: flex; align-items: center; justify-content: center; gap: 5px; margin-top: 20px; color: #7b8380; text-decoration: none; font-size: 11px; }.login-error { margin: -4px 0 0; color: #a14338; font-size: 11px; text-align: left; }
.admin-shell { height: 100vh; min-height: 0; display: flex; overflow: hidden; color: #252a2e; background: #f5f6f7; }
.admin-sidebar { position: sticky; top: 0; width: 232px; height: 100vh; display: flex; flex-direction: column; flex: 0 0 232px; padding: 22px 14px 16px; color: #dce4e1; background: #17231f; }
.admin-brand { display: flex; align-items: center; gap: 11px; padding: 0 8px 24px; border-bottom: 1px solid rgba(255,255,255,.09); }
.admin-brand img { width: 77px; border-radius: 3px; }
.admin-brand strong, .admin-brand span { display: block; }
.admin-brand strong { color: #fff; font-size: 13px; }.admin-brand span { margin-top: 3px; color: #8da099; font-size: 11px; }
.admin-sidebar nav { display: grid; gap: 4px; margin-top: 20px; }
.admin-sidebar nav a, .admin-sidebar nav span, .back-workspace { min-height: 40px; display: flex; align-items: center; gap: 10px; padding: 0 11px; color: #aebcb7; border-radius: 7px; text-decoration: none; font-size: 13px; }
.admin-sidebar nav a:hover, .admin-sidebar nav a.active { color: #fff; background: #263832; }.admin-sidebar nav a.active { font-weight: 600; }
.admin-sidebar nav .disabled { opacity: .48; }.admin-sidebar nav small { margin-left: auto; font-size: 9px; }
.admin-sidebar-footer { display: flex; align-items: center; justify-content: space-between; margin-top: auto; padding: 13px 10px; border-top: 1px solid rgba(255,255,255,.09); }.admin-sidebar-footer strong, .admin-sidebar-footer span { display: block; }.admin-sidebar-footer strong { color: #e7eeeb; font-size: 11px; }.admin-sidebar-footer span { margin-top: 3px; color: #82958e; font-size: 9px; }.admin-sidebar-footer button { width: 29px; height: 29px; display: grid; place-items: center; color: #aebcb7; background: transparent; border: 0; border-radius: 6px; }.admin-sidebar-footer button:hover { color: #fff; background: #2a3c36; }
.back-workspace { border-top: 1px solid rgba(255,255,255,.09); border-radius: 0; }.back-workspace:hover { color: #fff; }
.admin-content { min-width: 0; width: 100%; height: 100vh; overflow-y: auto; padding: 30px 34px 45px; }
.admin-topbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 25px; }.admin-topbar h1 { margin: 0; font-size: 25px; }.admin-topbar p { margin: 6px 0 0; color: #7b8380; font-size: 13px; }
.admin-topbar button { height: 35px; display: flex; align-items: center; gap: 7px; padding: 0 12px; color: #46504c; background: #fff; border: 1px solid #d9dddb; border-radius: 7px; }.admin-topbar button:hover:not(:disabled) { background: #f0f3f2; }.spinning { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.admin-error { margin-bottom: 18px; padding: 11px 14px; color: #923f35; background: #fbefed; border: 1px solid #efd2cd; border-radius: 8px; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.metric-grid article { min-width: 0; display: flex; align-items: center; gap: 13px; padding: 18px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }.metric-icon { width: 41px; height: 41px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 9px; }.metric-icon.green { color: #14795d; background: #e8f4f0; }.metric-icon.blue { color: #326e9d; background: #eaf2f8; }.metric-icon.orange { color: #a66122; background: #faf0e5; }.metric-icon.purple { color: #75549a; background: #f1ebf7; }
.metric-grid small, .metric-grid strong { display: block; }.metric-grid small { color: #818885; font-size: 11px; }.metric-grid strong { margin-top: 5px; font-size: 23px; }
.metric-grid.compact { margin-top: 16px; }.metric-grid.compact article { padding: 15px; background: #fafbfb; }
.admin-panel { margin-top: 17px; padding: 20px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }.panel-heading { display: flex; align-items: center; justify-content: space-between; }.panel-heading h2 { margin: 0; font-size: 15px; }.panel-heading p { margin: 5px 0 0; color: #8a918e; font-size: 11px; }.panel-heading > span { color: #7c8481; font-size: 11px; }
.panel-heading.slim { margin-top: 20px; }.panel-link { height: 32px; display: inline-flex; align-items: center; padding: 0 11px; color: #1e745a; background: #eaf4f0; border-radius: 6px; text-decoration: none; font-size: 11px; font-weight: 600; }.panel-link:hover { background: #dceee8; }
.panel-action { height: 32px; padding: 0 11px; color: #fff; background: #1f765c; border: 0; border-radius: 6px; font-size: 11px; }.panel-action:hover { background: #185f4a; }
.model-defaults { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; align-items: end; gap: 10px; margin-top: 16px; padding: 13px; background: #f7f9f8; border: 1px solid #e3e7e5; border-radius: 8px; }.model-defaults label { display: grid; gap: 6px; color: #59625e; font-size: 10px; font-weight: 600; }.model-defaults button { height: 34px; padding: 0 11px; color: #fff; background: #1e745a; border: 0; border-radius: 6px; font-size: 10px; }.model-defaults span { grid-column: 1 / -1; color: #66716c; font-size: 10px; }
.admin-select { width: 100%; }.admin-select :deep(.el-select__wrapper) { min-height: 36px; padding: 0 10px; background: #fff; border-radius: 7px; box-shadow: 0 0 0 1px #d8ddda inset; }.admin-select :deep(.el-select__wrapper:hover) { box-shadow: 0 0 0 1px #bfc9c4 inset; }.admin-select :deep(.el-select__wrapper.is-focused) { box-shadow: 0 0 0 1px #4e907b inset, 0 0 0 3px #eaf3f0; }.admin-select :deep(.el-select__selected-item) { color: #303633; font-size: 12px; font-weight: 500; }.admin-select :deep(.el-select__caret) { color: #7d8783; }
:global(.admin-select-dropdown.el-popper) { border: 1px solid #dfe5e2; border-radius: 9px; box-shadow: 0 12px 34px rgba(27, 43, 36, .14); }:global(.admin-select-dropdown .el-select-dropdown__item) { height: 34px; margin: 3px 6px; padding: 0 10px; border-radius: 6px; color: #4d5753; font-size: 12px; }:global(.admin-select-dropdown .el-select-dropdown__item.is-hovering) { background: #f2f6f4; }:global(.admin-select-dropdown .el-select-dropdown__item.is-selected) { color: #176f55; background: #e9f4ef; font-weight: 650; }
.model-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 17px; }.model-card { min-width: 0; padding: 15px; border: 1px solid #e4e7e5; border-radius: 9px; }.model-card-heading { display: flex; align-items: center; gap: 9px; }.model-card-heading > span { width: 32px; height: 32px; display: grid; place-items: center; color: #23765d; background: #e9f4f0; border-radius: 7px; }.model-card-heading strong, .model-card-heading small { display: block; }.model-card-heading strong { font-size: 12px; }.model-card-heading small { margin-top: 3px; color: #929895; font-size: 9px; }.model-card-heading i { margin-left: auto; padding: 3px 6px; color: #8a5951; background: #f8ecea; border-radius: 8px; font-size: 9px; font-style: normal; }.model-card-heading i.enabled { color: #187056; background: #e8f4ef; }.model-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 14px 0 10px; }.model-card dl div { display: flex; justify-content: space-between; gap: 6px; }.model-card dt { color: #969c99; font-size: 9px; }.model-card dd { margin: 0; color: #4e5753; font-size: 10px; }.model-card > p { overflow: hidden; margin: 0; color: #8a918e; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.model-card-actions { display: flex; gap: 5px; margin-top: 13px; }.model-card-actions button { height: 27px; padding: 0 8px; color: #5f6864; background: #f5f7f6; border: 1px solid #e1e5e3; border-radius: 5px; font-size: 9px; }.model-card-actions button:hover { background: #ebefed; }.model-card-actions button.danger:hover { color: #9a3f35; background: #faeeec; }.model-empty { grid-column: 1 / -1; padding: 25px; color: #939a97; text-align: center; font-size: 11px; }
.model-editor-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 20px; background: rgba(20, 28, 25, .45); }.model-editor { width: min(100%, 680px); max-height: calc(100vh - 40px); overflow-y: auto; padding: 22px; background: #fff; border-radius: 11px; box-shadow: 0 20px 60px rgba(0,0,0,.2); }.editor-close { width: 30px; height: 30px; color: #737b78; background: transparent; border: 0; border-radius: 6px; font-size: 21px; }.editor-close:hover { background: #f0f2f1; }.model-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; margin-top: 20px; }.model-form-grid label { display: grid; gap: 6px; color: #59625e; font-size: 10px; font-weight: 600; }.model-form-grid label.wide { grid-column: 1 / -1; }.model-form-grid input:not([type=checkbox]), .model-form-grid select { width: 100%; height: 36px; padding: 0 9px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 6px; outline: 0; }.model-form-grid input:focus, .model-form-grid select:focus { border-color: #4e907b; box-shadow: 0 0 0 3px #eaf3f0; }.model-form-grid .model-enabled { display: flex; grid-column: 1 / -1; align-items: center; gap: 8px; }.model-save-error { margin: 14px 0 0; padding: 10px 12px; color: #9b3f34; background: #fbefed; border: 1px solid #efd1cb; border-radius: 7px; font-size: 11px; line-height: 1.55; white-space: pre-wrap; }.editor-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 21px; }.editor-actions button { height: 34px; padding: 0 13px; color: #606966; background: #f4f6f5; border: 1px solid #dde1df; border-radius: 6px; }.editor-actions button.primary { color: #fff; background: #1e745a; border-color: #1e745a; }
.discovery-row { display: flex; align-items: center; gap: 9px; }.discovery-row button { height: 33px; padding: 0 10px; color: #176d53; background: #e8f3ef; border: 0; border-radius: 6px; font-size: 10px; }.discovery-row span { color: #66716c; font-size: 10px; line-height: 1.5; }.discovery-row span.error { color: #9b3f34; }
.prompt-admin-layout { display: grid; grid-template-columns: .8fr 1.2fr; gap: 16px; margin-top: 17px; }.prompt-admin-list { max-height: 290px; overflow-y: auto; }.prompt-admin-list article { padding: 11px; border: 1px solid #e3e6e4; border-radius: 7px; cursor: pointer; }.prompt-admin-list article + article { margin-top: 7px; }.prompt-admin-list article:hover { background: #f7f9f8; }.prompt-admin-list article > div { display: flex; justify-content: space-between; }.prompt-admin-list strong { font-size: 11px; }.prompt-admin-list span { color: #187056; font-size: 9px; }.prompt-admin-list span.off { color: #9a5148; }.prompt-admin-list p { margin: 6px 0 0; color: #8b928f; font-size: 9px; }.prompt-admin-form { display: grid; gap: 10px; }.prompt-admin-form label { display: grid; gap: 5px; color: #5d6662; font-size: 10px; font-weight: 600; }.prompt-admin-form input:not([type=checkbox]), .prompt-admin-form textarea { padding: 8px 9px; border: 1px solid #d8dcda; border-radius: 6px; outline: 0; resize: vertical; }.prompt-admin-form .prompt-enabled { display: flex; align-items: center; gap: 6px; }.prompt-admin-form > button { height: 34px; color: #fff; background: #1e745a; border: 0; border-radius: 6px; }
.usage-split { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 10px; }.usage-split > article { min-width: 0; padding: 14px; background: #f9fbfa; border: 1px solid #e5e9e7; border-radius: 8px; }.usage-chart { min-height: 190px; display: flex; align-items: flex-end; gap: 14px; padding: 25px 8px 0; opacity: 1; transition: opacity .15s; }.usage-chart.loading { opacity: .45; }.usage-column { min-width: 0; height: 165px; display: flex; flex: 1; flex-direction: column; align-items: center; }.bar-area { width: min(44px, 70%); height: 112px; display: flex; align-items: flex-end; }.usage-bar { width: 100%; min-height: 4px; background: linear-gradient(180deg, #42a887, #197559); border-radius: 5px 5px 2px 2px; }.usage-bar.api { background: linear-gradient(180deg, #5d91d5, #2f68a8); }.usage-column strong { margin-top: 8px; color: #59615e; font-size: 10px; }.usage-column span { margin-top: 5px; color: #949a98; font-size: 10px; }.empty-chart { width: 100%; align-self: center; color: #9aa09e; text-align: center; font-size: 12px; }
.table-wrap { margin-top: 16px; overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th { padding: 10px 12px; color: #8a918e; background: #f7f8f8; font-size: 10px; font-weight: 600; text-align: left; }td { padding: 12px; color: #59615e; border-bottom: 1px solid #eef0ef; }.table-user { display: flex; align-items: center; gap: 9px; }.table-user > span { width: 27px; height: 27px; display: grid; place-items: center; color: #fff; background: #527a6d; border-radius: 50%; font-size: 10px; }.table-user strong { color: #303633; font-size: 12px; }.status-pill { padding: 3px 7px; color: #197357; background: #e9f5f0; border-radius: 10px; font-size: 10px; }.status-pill.inactive { color: #8b4941; background: #f8eae8; }.empty-table { padding: 28px; color: #969c99; text-align: center; }
.admin-toggle { display: flex; align-items: center; gap: 6px; padding: 0; color: #7d8582; background: transparent; border: 0; font-size: 10px; }.admin-toggle > span { position: relative; width: 26px; height: 15px; background: #cdd2d0; border-radius: 8px; transition: background .15s; }.admin-toggle > span::after { position: absolute; top: 2px; left: 2px; width: 11px; height: 11px; content: ''; background: #fff; border-radius: 50%; transition: transform .15s; }.admin-toggle.enabled { color: #176f55; }.admin-toggle.enabled > span { background: #27856a; }.admin-toggle.enabled > span::after { transform: translateX(11px); }
.table-action { height: 28px; padding: 0 9px; color: #176f55; background: #e8f3ef; border: 0; border-radius: 6px; font-size: 10px; }.table-action:hover { background: #dceee8; }.user-search { display: flex; gap: 8px; margin-top: 16px; }.user-search input { min-width: 260px; height: 34px; padding: 0 11px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 7px; outline: 0; }.user-search input:focus { border-color: #4e907b; box-shadow: 0 0 0 3px #eaf3f0; }.user-search button, .pagination-row button { height: 34px; padding: 0 11px; color: #176f55; background: #e8f3ef; border: 0; border-radius: 7px; font-size: 11px; }.user-search button:hover:not(:disabled), .pagination-row button:hover:not(:disabled) { background: #dceee8; }.pagination-row { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 14px; color: #66716c; font-size: 11px; }.pagination-row button:disabled { opacity: .45; cursor: not-allowed; }.api-admin-notice { margin: 14px 0 0; padding: 10px 12px; color: #7a5732; background: #fff7e8; border: 1px solid #f1dbb7; border-radius: 7px; font-size: 11px; }.api-admin-section code { color: #2563eb; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }.api-admin-section small { color: #8b9490; font-size: 10px; }
.announcement-layout { display: block; margin-top: 17px; }.announcement-list { max-height: 560px; overflow-y: auto; padding-right: 4px; }.announcement-list article { padding: 13px; background: #fff; border: 1px solid #e2e6e4; border-radius: 8px; cursor: pointer; }.announcement-list article.locked { cursor: pointer; }.announcement-list article + article { margin-top: 8px; }.announcement-list article:hover, .announcement-list article.active { background: #f5f8f7; border-color: #b9cbc4; }.announcement-list article > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.announcement-list strong { overflow: hidden; color: #303633; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.announcement-list span { flex: 0 0 auto; padding: 3px 7px; color: #176f55; background: #e9f4ef; border-radius: 10px; font-size: 9px; }.announcement-list span.off { color: #875047; background: #f8ecea; }.announcement-list p { max-height: 42px; overflow: hidden; margin: 8px 0; color: #737c78; font-size: 10px; line-height: 1.45; }.announcement-list small { color: #9aa19e; font-size: 9px; }.announcement-empty { padding: 28px; color: #979f9b; text-align: center; font-size: 11px; }.announcement-form { display: grid; align-content: start; gap: 12px; padding: 16px; background: #f8faf9; border: 1px solid #e2e7e4; border-radius: 8px; }.announcement-form h3 { margin: 0 0 2px; color: #303633; font-size: 13px; }.announcement-form > label { display: grid; gap: 6px; color: #59625e; font-size: 10px; font-weight: 600; }.announcement-form input:not([type=checkbox]), .announcement-form textarea { width: 100%; padding: 8px 10px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 6px; outline: 0; resize: vertical; }.announcement-form input:focus, .announcement-form textarea:focus { border-color: #4e907b; box-shadow: 0 0 0 3px #eaf3f0; }.announcement-published { display: flex !important; align-items: center; gap: 7px; color: #23765d !important; }.announcement-actions { display: flex; align-items: center; gap: 10px; }.announcement-actions span { margin-right: auto; color: #66716c; font-size: 10px; }.announcement-actions button { height: 34px; padding: 0 12px; color: #5f6864; background: #fff; border: 1px solid #d9dfdc; border-radius: 6px; font-size: 10px; }.announcement-actions button.primary { color: #fff; background: #1e745a; border-color: #1e745a; }
.admin-modal-backdrop { position: fixed; inset: 0; z-index: 55; display: grid; place-items: center; padding: 20px; background: rgba(20, 28, 25, .45); }.admin-modal { width: min(100%, 640px); max-height: calc(100vh - 40px); overflow-y: auto; padding: 22px; background: #fff; border-radius: 11px; box-shadow: 0 20px 60px rgba(0,0,0,.2); }.announcement-editor-modal { display: grid; gap: 13px; }.announcement-editor-modal > label { display: grid; gap: 6px; color: #59625e; font-size: 10px; font-weight: 600; }.announcement-editor-modal input, .announcement-editor-modal textarea { width: 100%; padding: 9px 10px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 6px; outline: 0; resize: vertical; }.announcement-actions.fixed { justify-content: flex-end; }.announcement-actions.fixed span { margin-right: auto; }.announcement-detail-modal p { max-height: 55vh; overflow-y: auto; margin: 16px 0 0; color: #4d5753; font-size: 13px; line-height: 1.75; white-space: pre-wrap; }
.quota-editor-modal { width: min(100%, 460px); }.quota-editor-modal > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.quota-editor-modal h2 { margin: 0; font-size: 18px; }.quota-editor-modal header p { margin: 6px 0 0; color: #7a837f; font-size: 12px; }.quota-editor-modal > label { display: grid; gap: 7px; margin-top: 17px; color: #59625e; font-size: 11px; font-weight: 600; }.quota-editor-modal input { width: 100%; height: 38px; padding: 0 10px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 6px; outline: 0; }.quota-mode-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin-top: 20px; padding: 4px; background: #f1f3f2; border-radius: 7px; }.quota-mode-switch button { height: 35px; color: #68716d; background: transparent; border: 0; border-radius: 5px; font-weight: 600; }.quota-mode-switch button.active { color: #fff; background: #1e745a; box-shadow: 0 1px 3px rgba(20, 45, 36, .18); }.quota-unlimited-hint { margin: 17px 0 0; padding: 11px 12px; color: #496157; background: #eef6f3; border-radius: 7px; font-size: 12px; line-height: 1.6; }
.settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 16px; }.settings-grid article { padding: 15px; background: #f7f8f8; border: 1px solid #e6e8e7; border-radius: 7px; }.settings-grid strong, .settings-grid span { display: block; }.settings-grid strong { color: #3c4440; font-size: 11px; }.settings-grid span { margin-top: 6px; color: #838b87; font-size: 10px; }
@media (max-width: 900px) { .admin-sidebar { width: 188px; flex-basis: 188px; }.admin-brand img { width: 62px; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.model-grid { grid-template-columns: 1fr 1fr; }.admin-content { padding: 24px 20px 36px; } }
@media (max-width: 650px) { .admin-shell { display: block; }.admin-sidebar { position: static; width: 100%; height: auto; padding: 13px; }.admin-brand { padding-bottom: 12px; }.admin-sidebar nav { display: flex; overflow-x: auto; margin-top: 12px; }.admin-sidebar nav a, .admin-sidebar nav span { flex: 0 0 auto; }.back-workspace { margin-top: 10px; padding-top: 10px; }.admin-content { padding: 20px 12px 30px; }.metric-grid, .model-defaults, .model-grid, .model-form-grid, .prompt-admin-layout, .announcement-layout, .settings-grid, .usage-split { grid-template-columns: 1fr; }.announcement-actions, .user-search { align-items: flex-start; flex-direction: column; }.user-search input { width: 100%; min-width: 0; }.announcement-actions span { margin-right: 0; }.model-form-grid label.wide { grid-column: auto; }.usage-chart { gap: 4px; padding-left: 0; padding-right: 0; }.admin-topbar { align-items: flex-start; } }
</style>
