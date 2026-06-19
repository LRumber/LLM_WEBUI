<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Activity,
  Bot,
  LayoutDashboard,
  LockKeyhole,
  LogOut,
  MessageSquare,
  RefreshCw,
  Settings,
  ShieldCheck,
  Users,
  Zap,
} from 'lucide-vue-next'
import { adminPageMeta, resolveAdminPage } from './adminRouting'

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

interface AdminUser {
  id: number
  accountNumber: string
  displayName: string
  status: number
  administrator: boolean
  lastLoginAt: string | null
  conversationCount: number
  totalTokens: number
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
  base_url: string
  model_name: string
  capabilities: string[]
  enabled: boolean
  context_window?: number
  quantization?: string
  tensor_parallel_size: number
  gpu_ids: number[]
  gpu_memory_utilization: number
  max_concurrency: number
  api_key_env?: string
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
const users = ref<AdminUser[]>([])
const models = ref<ModelRegistration[]>([])
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
const modelHealth = ref<Record<string, string>>({})
const modelForm = ref<ModelRegistration>({
  key: '', display_name: '', provider: 'local', engine: 'vllm', base_url: '', model_name: '',
  capabilities: ['chat'], enabled: true, context_window: 32768, quantization: '',
  tensor_parallel_size: 1, gpu_ids: [], gpu_memory_utilization: 0.9, max_concurrency: 4,
})
const templateForm = ref({ id: '', name: '', description: '', systemPrompt: '', enabled: true })

const adminPage = computed(() => resolveAdminPage(window.location.pathname))
const pageMeta = computed(() => adminPageMeta[adminPage.value])

const maxDailyTokens = computed(() => {
  return Math.max(...dailyUsage.value.map((day) => day.inputTokens + day.outputTokens), 1)
})

const formatNumber = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric',
  day: 'numeric',
}).format(new Date(`${value}T00:00:00`))
const formatDateTime = (value: string | null) => value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '尚未登录'

/** Loads only the API data required by the current administration route. */
const loadCurrentPage = async () => {
  loading.value = true
  loadError.value = false
  try {
    if (adminPage.value === 'overview') {
      const response = await fetch('/api/admin/overview')
      if (!response.ok) throw new Error('Overview unavailable')
      overview.value = await response.json()
    } else if (adminPage.value === 'users') {
      const response = await fetch('/api/admin/users')
      if (!response.ok) throw new Error('Users unavailable')
      users.value = await response.json()
    } else if (adminPage.value === 'usage') {
      const response = await fetch('/api/admin/usage/daily?days=7')
      if (!response.ok) throw new Error('Usage unavailable')
      dailyUsage.value = await response.json()
    } else if (adminPage.value === 'models') {
      await loadModels()
    } else if (adminPage.value === 'prompts') {
      await loadSystemTemplates()
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
    const response = await fetch('/api/admin/models')
    if (response.status === 401) {
      currentAdmin.value = null
      return
    }
    models.value = response.ok ? await response.json() : []
  } catch {
    models.value = []
  }
}

/** Loads system prompt templates, including disabled records visible only to administrators. */
const loadSystemTemplates = async () => {
  const response = await fetch('/api/admin/prompt-templates')
  systemTemplates.value = response.ok ? await response.json() : []
}

const checkSession = async () => {
  try {
    const response = await fetch('/api/admin/auth/me')
    if (response.ok) {
      currentAdmin.value = await response.json()
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
    await loadCurrentPage()
  } catch {
    loginError.value = '无法连接管理服务'
  } finally {
    loginSubmitting.value = false
  }
}

const logout = async () => {
  await fetch('/api/admin/auth/logout', { method: 'POST' })
  currentAdmin.value = null
  users.value = []
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

/** Opens a clean registration editor for an OpenAI-compatible inference endpoint. */
const createModel = () => {
  modelForm.value = {
    key: '', display_name: '', provider: 'local', engine: 'vllm', base_url: '', model_name: '',
    capabilities: ['chat'], enabled: true, context_window: 32768, quantization: '',
    tensor_parallel_size: 1, gpu_ids: [], gpu_memory_utilization: 0.9, max_concurrency: 4,
  }
  modelEditorOpen.value = true
}

/** Copies an existing registration so edits remain cancelable until explicitly saved. */
const editModel = (model: ModelRegistration) => {
  modelForm.value = JSON.parse(JSON.stringify(model))
  modelEditorOpen.value = true
}

const gpuIdsText = computed({
  get: () => modelForm.value.gpu_ids.join(','),
  set: (value: string) => {
    modelForm.value.gpu_ids = value.split(',').map((item) => Number(item.trim()))
      .filter((item) => Number.isInteger(item) && item >= 0)
  },
})

const capabilitiesText = computed({
  get: () => modelForm.value.capabilities.join(','),
  set: (value: string) => {
    modelForm.value.capabilities = value.split(',').map((item) => item.trim()).filter(Boolean)
  },
})

/** Persists engine, quantization, GPU placement, capability, and concurrency settings atomically. */
const saveModel = async () => {
  modelSaving.value = true
  try {
    const response = await fetch(`/api/admin/models/${encodeURIComponent(modelForm.value.key)}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(modelForm.value),
    })
    if (response.ok) {
      modelEditorOpen.value = false
      await loadModels()
    }
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

onMounted(checkSession)
</script>

<template>
  <main v-if="sessionChecked && !currentAdmin" class="admin-login-page">
    <section class="admin-login-card">
      <div class="admin-login-mark"><LockKeyhole :size="23" /></div>
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
        <a :class="{ active: adminPage === 'prompts' }" href="/admin/prompts"><MessageSquare :size="18" />提示词模板</a>
        <a :class="{ active: adminPage === 'settings' }" href="/admin/settings"><Settings :size="18" />系统设置</a>
      </nav>
      <div class="admin-sidebar-footer">
        <div><strong>{{ currentAdmin.displayName }}</strong><span>本地管理员</span></div>
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
        <article><span class="metric-icon orange"><Zap :size="20" /></span><div><small>今日请求</small><strong>{{ formatNumber(overview.todayRequestCount) }}</strong></div></article>
        <article><span class="metric-icon purple"><Activity :size="20" /></span><div><small>今日 Token</small><strong>{{ formatNumber(overview.todayTokenCount) }}</strong></div></article>
      </div>

      <section v-if="adminPage === 'models'" class="admin-panel model-panel">
        <div class="panel-heading">
          <div><h2>模型管理</h2><p>动态注册 OpenAI 兼容端点、推理引擎与 GPU 并发参数</p></div>
          <button class="panel-action" type="button" @click="createModel">新增模型</button>
        </div>
        <div class="model-grid">
          <article v-for="model in models" :key="model.key" class="model-card">
            <div class="model-card-heading"><span><Bot :size="18" /></span><div><strong>{{ model.display_name }}</strong><small>{{ model.key }}</small></div><i :class="{ enabled: model.enabled }">{{ model.enabled ? '启用' : '停用' }}</i></div>
            <dl><div><dt>引擎</dt><dd>{{ model.engine }}</dd></div><div><dt>量化</dt><dd>{{ model.quantization || '无' }}</dd></div><div><dt>GPU</dt><dd>{{ model.gpu_ids.length ? model.gpu_ids.join(', ') : '自动' }}</dd></div><div><dt>并发</dt><dd>{{ model.max_concurrency }}</dd></div></dl>
            <p>{{ model.base_url }}</p>
            <div class="model-card-actions"><button type="button" @click="checkModel(model)">{{ modelHealth[model.key] || '健康检查' }}</button><button type="button" @click="editModel(model)">编辑</button><button class="danger" type="button" @click="deleteModel(model)">移除</button></div>
          </article>
          <p v-if="!models.length" class="model-empty">AI 服务尚无模型注册或当前不可用。</p>
        </div>
      </section>

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

      <div v-if="adminPage === 'models' && modelEditorOpen" class="model-editor-backdrop" @click.self="modelEditorOpen = false">
        <form class="model-editor" @submit.prevent="saveModel">
          <div class="panel-heading"><div><h2>{{ models.some((item) => item.key === modelForm.key) ? '编辑模型' : '新增模型' }}</h2><p>所有推理引擎必须提供 OpenAI 兼容端点</p></div><button class="editor-close" type="button" @click="modelEditorOpen = false">×</button></div>
          <div class="model-form-grid">
            <label>模型标识<input v-model="modelForm.key" pattern="[a-z0-9][a-z0-9._-]+" required /></label>
            <label>显示名称<input v-model="modelForm.display_name" required /></label>
            <label>推理引擎<select v-model="modelForm.engine"><option>vllm</option><option>sglang</option><option>ollama</option><option>llama.cpp</option><option>openai-compatible</option></select></label>
            <label>Provider<input v-model="modelForm.provider" required /></label>
            <label class="wide">API 地址<input v-model="modelForm.base_url" type="url" placeholder="http://127.0.0.1:8000/v1" required /></label>
            <label class="wide">模型名称/路径<input v-model="modelForm.model_name" required /></label>
            <label>量化版本<input v-model="modelForm.quantization" placeholder="AWQ / GPTQ / Q4_K_M" /></label>
            <label>上下文窗口<input v-model.number="modelForm.context_window" type="number" min="1" /></label>
            <label>GPU 编号<input v-model="gpuIdsText" placeholder="0,1" /></label>
            <label>张量并行<input v-model.number="modelForm.tensor_parallel_size" type="number" min="1" max="64" /></label>
            <label>显存利用率<input v-model.number="modelForm.gpu_memory_utilization" type="number" min="0.01" max="1" step="0.01" /></label>
            <label>最大并发<input v-model.number="modelForm.max_concurrency" type="number" min="1" max="1024" /></label>
            <label class="wide">能力（逗号分隔）<input v-model="capabilitiesText" placeholder="chat,vision" required /></label>
            <label class="wide">密钥环境变量<input v-model="modelForm.api_key_env" placeholder="留空表示本地无密钥端点" /></label>
            <label class="model-enabled"><input v-model="modelForm.enabled" type="checkbox" />允许用户选择该模型</label>
          </div>
          <div class="editor-actions"><button type="button" @click="modelEditorOpen = false">取消</button><button class="primary" type="submit" :disabled="modelSaving">{{ modelSaving ? '保存中…' : '保存模型' }}</button></div>
        </form>
      </div>

      <section v-if="adminPage === 'usage'" class="admin-panel usage-panel">
        <div class="panel-heading"><div><h2>近 7 天用量</h2><p>每日输入与输出 Token 总量</p></div></div>
        <div class="usage-chart" :class="{ loading }">
          <div v-for="day in dailyUsage" :key="day.date" class="usage-column">
            <div class="bar-area" :title="`${formatNumber(day.inputTokens + day.outputTokens)} Token`">
              <div class="usage-bar" :style="{ height: `${Math.max(4, ((day.inputTokens + day.outputTokens) / maxDailyTokens) * 100)}%` }"></div>
            </div>
            <strong>{{ formatNumber(day.requestCount) }} 次</strong>
            <span>{{ formatDate(day.date) }}</span>
          </div>
          <p v-if="!dailyUsage.length && !loading" class="empty-chart">暂无用量数据</p>
        </div>
      </section>

      <section v-if="adminPage === 'users'" class="admin-panel user-panel">
        <div class="panel-heading"><div><h2>用户概况</h2><p>最近同步的 100 个影子用户及累计用量</p></div><span>{{ users.length }} 位用户</span></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>用户</th><th>账号</th><th>状态</th><th>后台权限</th><th>会话数</th><th>累计 Token</th><th>最近登录</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td><div class="table-user"><span>{{ user.displayName.slice(0, 1) }}</span><strong>{{ user.displayName }}</strong></div></td>
                <td>{{ user.accountNumber }}</td>
                <td><span class="status-pill" :class="{ inactive: user.status !== 1 }">{{ user.status === 1 ? '正常' : '停用' }}</span></td>
                <td><button class="admin-toggle" :class="{ enabled: user.administrator }" type="button" @click="setAdministrator(user)"><span></span>{{ user.administrator ? '管理员' : '普通用户' }}</button></td>
                <td>{{ formatNumber(user.conversationCount) }}</td>
                <td>{{ formatNumber(user.totalTokens) }}</td>
                <td>{{ formatDateTime(user.lastLoginAt) }}</td>
              </tr>
              <tr v-if="!users.length && !loading"><td colspan="7" class="empty-table">暂无用户数据</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="adminPage === 'settings'" class="admin-panel settings-panel">
        <div class="panel-heading"><div><h2>系统设置</h2><p>部署参数通过环境变量和离线配置文件管理</p></div></div>
        <div class="settings-grid"><article><strong>认证模式</strong><span>本地管理员 + OAuth2 用户</span></article><article><strong>对象存储</strong><span>MinIO 私有 Bucket</span></article><article><strong>异步任务</strong><span>有界后台执行器</span></article><article><strong>配置原则</strong><span>密钥不写入浏览器或数据库明文</span></article></div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.admin-login-page { min-height: 100vh; display: grid; place-items: center; padding: 24px; color: #29302d; background: radial-gradient(circle at 50% 0, #edf5f2, #f5f6f7 45%); }
.admin-login-card { width: min(100%, 390px); padding: 34px; background: #fff; border: 1px solid #e1e5e3; border-radius: 13px; box-shadow: 0 18px 50px rgba(28, 50, 42, .09); }.admin-login-mark { width: 46px; height: 46px; display: grid; place-items: center; margin-bottom: 19px; color: #fff; background: #1c6f57; border-radius: 11px; }.admin-login-eyebrow { margin: 0 0 5px; color: #298166; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.admin-login-card h1 { margin: 0; font-size: 24px; }.admin-login-copy { margin: 10px 0 23px; color: #7b8380; font-size: 12px; line-height: 1.6; }.admin-login-card form { display: grid; gap: 14px; }.admin-login-card label { display: grid; gap: 6px; color: #555e5a; font-size: 11px; font-weight: 600; }.admin-login-card input { height: 39px; padding: 0 11px; color: #2e3431; background: #fff; border: 1px solid #d6dbd8; border-radius: 7px; outline: 0; }.admin-login-card input:focus { border-color: #4f8f7b; box-shadow: 0 0 0 3px #e8f2ef; }.admin-login-card form > button { height: 41px; display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 3px; color: #fff; background: #1d7159; border: 0; border-radius: 7px; font-weight: 600; }.admin-login-card form > button:hover:not(:disabled) { background: #155d48; }.admin-login-card > a { display: flex; align-items: center; justify-content: center; gap: 5px; margin-top: 20px; color: #7b8380; text-decoration: none; font-size: 11px; }.login-error { margin: -4px 0 0; color: #a14338; font-size: 11px; }
.admin-shell { min-height: 100vh; display: flex; color: #252a2e; background: #f5f6f7; }
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
.admin-content { min-width: 0; width: 100%; padding: 30px 34px 45px; }
.admin-topbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 25px; }.admin-topbar h1 { margin: 0; font-size: 25px; }.admin-topbar p { margin: 6px 0 0; color: #7b8380; font-size: 13px; }
.admin-topbar button { height: 35px; display: flex; align-items: center; gap: 7px; padding: 0 12px; color: #46504c; background: #fff; border: 1px solid #d9dddb; border-radius: 7px; }.admin-topbar button:hover:not(:disabled) { background: #f0f3f2; }.spinning { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.admin-error { margin-bottom: 18px; padding: 11px 14px; color: #923f35; background: #fbefed; border: 1px solid #efd2cd; border-radius: 8px; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.metric-grid article { min-width: 0; display: flex; align-items: center; gap: 13px; padding: 18px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }.metric-icon { width: 41px; height: 41px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 9px; }.metric-icon.green { color: #14795d; background: #e8f4f0; }.metric-icon.blue { color: #326e9d; background: #eaf2f8; }.metric-icon.orange { color: #a66122; background: #faf0e5; }.metric-icon.purple { color: #75549a; background: #f1ebf7; }
.metric-grid small, .metric-grid strong { display: block; }.metric-grid small { color: #818885; font-size: 11px; }.metric-grid strong { margin-top: 5px; font-size: 23px; }
.admin-panel { margin-top: 17px; padding: 20px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }.panel-heading { display: flex; align-items: center; justify-content: space-between; }.panel-heading h2 { margin: 0; font-size: 15px; }.panel-heading p { margin: 5px 0 0; color: #8a918e; font-size: 11px; }.panel-heading > span { color: #7c8481; font-size: 11px; }
.panel-action { height: 32px; padding: 0 11px; color: #fff; background: #1f765c; border: 0; border-radius: 6px; font-size: 11px; }.panel-action:hover { background: #185f4a; }
.model-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 17px; }.model-card { min-width: 0; padding: 15px; border: 1px solid #e4e7e5; border-radius: 9px; }.model-card-heading { display: flex; align-items: center; gap: 9px; }.model-card-heading > span { width: 32px; height: 32px; display: grid; place-items: center; color: #23765d; background: #e9f4f0; border-radius: 7px; }.model-card-heading strong, .model-card-heading small { display: block; }.model-card-heading strong { font-size: 12px; }.model-card-heading small { margin-top: 3px; color: #929895; font-size: 9px; }.model-card-heading i { margin-left: auto; padding: 3px 6px; color: #8a5951; background: #f8ecea; border-radius: 8px; font-size: 9px; font-style: normal; }.model-card-heading i.enabled { color: #187056; background: #e8f4ef; }.model-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 14px 0 10px; }.model-card dl div { display: flex; justify-content: space-between; gap: 6px; }.model-card dt { color: #969c99; font-size: 9px; }.model-card dd { margin: 0; color: #4e5753; font-size: 10px; }.model-card > p { overflow: hidden; margin: 0; color: #8a918e; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.model-card-actions { display: flex; gap: 5px; margin-top: 13px; }.model-card-actions button { height: 27px; padding: 0 8px; color: #5f6864; background: #f5f7f6; border: 1px solid #e1e5e3; border-radius: 5px; font-size: 9px; }.model-card-actions button:hover { background: #ebefed; }.model-card-actions button.danger:hover { color: #9a3f35; background: #faeeec; }.model-empty { grid-column: 1 / -1; padding: 25px; color: #939a97; text-align: center; font-size: 11px; }
.model-editor-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 20px; background: rgba(20, 28, 25, .45); }.model-editor { width: min(100%, 680px); max-height: calc(100vh - 40px); overflow-y: auto; padding: 22px; background: #fff; border-radius: 11px; box-shadow: 0 20px 60px rgba(0,0,0,.2); }.editor-close { width: 30px; height: 30px; color: #737b78; background: transparent; border: 0; border-radius: 6px; font-size: 21px; }.editor-close:hover { background: #f0f2f1; }.model-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; margin-top: 20px; }.model-form-grid label { display: grid; gap: 6px; color: #59625e; font-size: 10px; font-weight: 600; }.model-form-grid label.wide { grid-column: 1 / -1; }.model-form-grid input:not([type=checkbox]), .model-form-grid select { width: 100%; height: 36px; padding: 0 9px; color: #303633; background: #fff; border: 1px solid #d8ddda; border-radius: 6px; outline: 0; }.model-form-grid input:focus, .model-form-grid select:focus { border-color: #4e907b; box-shadow: 0 0 0 3px #eaf3f0; }.model-form-grid .model-enabled { display: flex; grid-column: 1 / -1; align-items: center; gap: 8px; }.editor-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 21px; }.editor-actions button { height: 34px; padding: 0 13px; color: #606966; background: #f4f6f5; border: 1px solid #dde1df; border-radius: 6px; }.editor-actions button.primary { color: #fff; background: #1e745a; border-color: #1e745a; }
.prompt-admin-layout { display: grid; grid-template-columns: .8fr 1.2fr; gap: 16px; margin-top: 17px; }.prompt-admin-list { max-height: 290px; overflow-y: auto; }.prompt-admin-list article { padding: 11px; border: 1px solid #e3e6e4; border-radius: 7px; cursor: pointer; }.prompt-admin-list article + article { margin-top: 7px; }.prompt-admin-list article:hover { background: #f7f9f8; }.prompt-admin-list article > div { display: flex; justify-content: space-between; }.prompt-admin-list strong { font-size: 11px; }.prompt-admin-list span { color: #187056; font-size: 9px; }.prompt-admin-list span.off { color: #9a5148; }.prompt-admin-list p { margin: 6px 0 0; color: #8b928f; font-size: 9px; }.prompt-admin-form { display: grid; gap: 10px; }.prompt-admin-form label { display: grid; gap: 5px; color: #5d6662; font-size: 10px; font-weight: 600; }.prompt-admin-form input:not([type=checkbox]), .prompt-admin-form textarea { padding: 8px 9px; border: 1px solid #d8dcda; border-radius: 6px; outline: 0; resize: vertical; }.prompt-admin-form .prompt-enabled { display: flex; align-items: center; gap: 6px; }.prompt-admin-form > button { height: 34px; color: #fff; background: #1e745a; border: 0; border-radius: 6px; }
.usage-chart { min-height: 190px; display: flex; align-items: flex-end; gap: 14px; padding: 25px 8px 0; opacity: 1; transition: opacity .15s; }.usage-chart.loading { opacity: .45; }.usage-column { min-width: 0; height: 165px; display: flex; flex: 1; flex-direction: column; align-items: center; }.bar-area { width: min(44px, 70%); height: 112px; display: flex; align-items: flex-end; }.usage-bar { width: 100%; min-height: 4px; background: linear-gradient(180deg, #42a887, #197559); border-radius: 5px 5px 2px 2px; }.usage-column strong { margin-top: 8px; color: #59615e; font-size: 10px; }.usage-column span { margin-top: 5px; color: #949a98; font-size: 10px; }.empty-chart { width: 100%; align-self: center; color: #9aa09e; text-align: center; font-size: 12px; }
.table-wrap { margin-top: 16px; overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th { padding: 10px 12px; color: #8a918e; background: #f7f8f8; font-size: 10px; font-weight: 600; text-align: left; }td { padding: 12px; color: #59615e; border-bottom: 1px solid #eef0ef; }.table-user { display: flex; align-items: center; gap: 9px; }.table-user > span { width: 27px; height: 27px; display: grid; place-items: center; color: #fff; background: #527a6d; border-radius: 50%; font-size: 10px; }.table-user strong { color: #303633; font-size: 12px; }.status-pill { padding: 3px 7px; color: #197357; background: #e9f5f0; border-radius: 10px; font-size: 10px; }.status-pill.inactive { color: #8b4941; background: #f8eae8; }.empty-table { padding: 28px; color: #969c99; text-align: center; }
.admin-toggle { display: flex; align-items: center; gap: 6px; padding: 0; color: #7d8582; background: transparent; border: 0; font-size: 10px; }.admin-toggle > span { position: relative; width: 26px; height: 15px; background: #cdd2d0; border-radius: 8px; transition: background .15s; }.admin-toggle > span::after { position: absolute; top: 2px; left: 2px; width: 11px; height: 11px; content: ''; background: #fff; border-radius: 50%; transition: transform .15s; }.admin-toggle.enabled { color: #176f55; }.admin-toggle.enabled > span { background: #27856a; }.admin-toggle.enabled > span::after { transform: translateX(11px); }
.settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 16px; }.settings-grid article { padding: 15px; background: #f7f8f8; border: 1px solid #e6e8e7; border-radius: 7px; }.settings-grid strong, .settings-grid span { display: block; }.settings-grid strong { color: #3c4440; font-size: 11px; }.settings-grid span { margin-top: 6px; color: #838b87; font-size: 10px; }
@media (max-width: 900px) { .admin-sidebar { width: 188px; flex-basis: 188px; }.admin-brand img { width: 62px; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.model-grid { grid-template-columns: 1fr 1fr; }.admin-content { padding: 24px 20px 36px; } }
@media (max-width: 650px) { .admin-shell { display: block; }.admin-sidebar { position: static; width: 100%; height: auto; padding: 13px; }.admin-brand { padding-bottom: 12px; }.admin-sidebar nav { display: flex; overflow-x: auto; margin-top: 12px; }.admin-sidebar nav a, .admin-sidebar nav span { flex: 0 0 auto; }.back-workspace { margin-top: 10px; padding-top: 10px; }.admin-content { padding: 20px 12px 30px; }.metric-grid, .model-grid, .model-form-grid, .prompt-admin-layout, .settings-grid { grid-template-columns: 1fr; }.model-form-grid label.wide { grid-column: auto; }.usage-chart { gap: 4px; padding-left: 0; padding-right: 0; }.admin-topbar { align-items: flex-start; } }
</style>
