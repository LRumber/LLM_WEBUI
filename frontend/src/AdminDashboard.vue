<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Activity,
  ArrowLeft,
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

const overview = ref<AdminOverview>({
  userCount: 0,
  conversationCount: 0,
  todayRequestCount: 0,
  todayTokenCount: 0,
})
const dailyUsage = ref<DailyUsage[]>([])
const users = ref<AdminUser[]>([])
const loading = ref(true)
const loadError = ref(false)
const sessionChecked = ref(false)
const currentAdmin = ref<AdminPrincipal | null>(null)
const loginUsername = ref('')
const loginPassword = ref('')
const loginError = ref('')
const loginSubmitting = ref(false)

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

const loadDashboard = async () => {
  loading.value = true
  loadError.value = false
  try {
    const [overviewResponse, usageResponse, usersResponse] = await Promise.all([
      fetch('/api/admin/overview'),
      fetch('/api/admin/usage/daily?days=7'),
      fetch('/api/admin/users'),
    ])
    if ([overviewResponse, usageResponse, usersResponse].some((response) => response.status === 401)) {
      currentAdmin.value = null
      return
    }
    if (![overviewResponse, usageResponse, usersResponse].every((response) => response.ok)) {
      throw new Error('Admin API unavailable')
    }
    overview.value = await overviewResponse.json()
    dailyUsage.value = await usageResponse.json()
    users.value = await usersResponse.json()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const checkSession = async () => {
  try {
    const response = await fetch('/api/admin/auth/me')
    if (response.ok) {
      currentAdmin.value = await response.json()
      await loadDashboard()
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
    await loadDashboard()
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
      <a href="/app"><ArrowLeft :size="15" />返回工作台</a>
    </section>
  </main>

  <main v-else-if="currentAdmin" class="admin-shell">
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <img src="/brand/sitp-logo.jpg" alt="SITP" />
        <div><strong>智能工作台</strong><span>管理后台</span></div>
      </div>
      <nav aria-label="后台导航">
        <a class="active" href="/admin"><LayoutDashboard :size="18" />概览</a>
        <a href="#users"><Users :size="18" />用户管理</a>
        <a href="#usage"><Activity :size="18" />用量统计</a>
        <span class="disabled"><Bot :size="18" />模型管理<small>待建设</small></span>
        <span class="disabled"><Settings :size="18" />系统设置<small>待建设</small></span>
      </nav>
      <div class="admin-sidebar-footer">
        <div><strong>{{ currentAdmin.displayName }}</strong><span>本地管理员</span></div>
        <button type="button" title="退出登录" @click="logout"><LogOut :size="16" /></button>
      </div>
      <a class="back-workspace" href="/app"><ArrowLeft :size="17" />返回工作台</a>
    </aside>

    <section class="admin-content">
      <header class="admin-topbar">
        <div><h1>平台概览</h1><p>用户、对话与模型资源使用情况</p></div>
        <button type="button" :disabled="loading" @click="loadDashboard">
          <RefreshCw :size="16" :class="{ spinning: loading }" />刷新
        </button>
      </header>

      <div v-if="loadError" class="admin-error">暂时无法加载后台数据，请确认核心服务和数据库已启动。</div>

      <div class="metric-grid" aria-label="平台指标">
        <article><span class="metric-icon green"><Users :size="20" /></span><div><small>平台用户</small><strong>{{ formatNumber(overview.userCount) }}</strong></div></article>
        <article><span class="metric-icon blue"><MessageSquare :size="20" /></span><div><small>有效会话</small><strong>{{ formatNumber(overview.conversationCount) }}</strong></div></article>
        <article><span class="metric-icon orange"><Zap :size="20" /></span><div><small>今日请求</small><strong>{{ formatNumber(overview.todayRequestCount) }}</strong></div></article>
        <article><span class="metric-icon purple"><Activity :size="20" /></span><div><small>今日 Token</small><strong>{{ formatNumber(overview.todayTokenCount) }}</strong></div></article>
      </div>

      <section id="usage" class="admin-panel usage-panel">
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

      <section id="users" class="admin-panel user-panel">
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
.usage-chart { min-height: 190px; display: flex; align-items: flex-end; gap: 14px; padding: 25px 8px 0; opacity: 1; transition: opacity .15s; }.usage-chart.loading { opacity: .45; }.usage-column { min-width: 0; height: 165px; display: flex; flex: 1; flex-direction: column; align-items: center; }.bar-area { width: min(44px, 70%); height: 112px; display: flex; align-items: flex-end; }.usage-bar { width: 100%; min-height: 4px; background: linear-gradient(180deg, #42a887, #197559); border-radius: 5px 5px 2px 2px; }.usage-column strong { margin-top: 8px; color: #59615e; font-size: 10px; }.usage-column span { margin-top: 5px; color: #949a98; font-size: 10px; }.empty-chart { width: 100%; align-self: center; color: #9aa09e; text-align: center; font-size: 12px; }
.table-wrap { margin-top: 16px; overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th { padding: 10px 12px; color: #8a918e; background: #f7f8f8; font-size: 10px; font-weight: 600; text-align: left; }td { padding: 12px; color: #59615e; border-bottom: 1px solid #eef0ef; }.table-user { display: flex; align-items: center; gap: 9px; }.table-user > span { width: 27px; height: 27px; display: grid; place-items: center; color: #fff; background: #527a6d; border-radius: 50%; font-size: 10px; }.table-user strong { color: #303633; font-size: 12px; }.status-pill { padding: 3px 7px; color: #197357; background: #e9f5f0; border-radius: 10px; font-size: 10px; }.status-pill.inactive { color: #8b4941; background: #f8eae8; }.empty-table { padding: 28px; color: #969c99; text-align: center; }
.admin-toggle { display: flex; align-items: center; gap: 6px; padding: 0; color: #7d8582; background: transparent; border: 0; font-size: 10px; }.admin-toggle > span { position: relative; width: 26px; height: 15px; background: #cdd2d0; border-radius: 8px; transition: background .15s; }.admin-toggle > span::after { position: absolute; top: 2px; left: 2px; width: 11px; height: 11px; content: ''; background: #fff; border-radius: 50%; transition: transform .15s; }.admin-toggle.enabled { color: #176f55; }.admin-toggle.enabled > span { background: #27856a; }.admin-toggle.enabled > span::after { transform: translateX(11px); }
@media (max-width: 900px) { .admin-sidebar { width: 188px; flex-basis: 188px; }.admin-brand img { width: 62px; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.admin-content { padding: 24px 20px 36px; } }
@media (max-width: 650px) { .admin-shell { display: block; }.admin-sidebar { position: static; width: 100%; height: auto; padding: 13px; }.admin-brand { padding-bottom: 12px; }.admin-sidebar nav { display: flex; overflow-x: auto; margin-top: 12px; }.admin-sidebar nav a, .admin-sidebar nav span { flex: 0 0 auto; }.back-workspace { margin-top: 10px; padding-top: 10px; }.admin-content { padding: 20px 12px 30px; }.metric-grid { grid-template-columns: 1fr 1fr; }.usage-chart { gap: 4px; padding-left: 0; padding-right: 0; }.admin-topbar { align-items: flex-start; } }
</style>
