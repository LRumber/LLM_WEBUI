<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import {
  ElAlert, ElBadge, ElButton, ElDatePicker, ElDescriptions, ElDescriptionsItem,
  ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPagination,
  ElRadio, ElRadioGroup, ElSelect, ElTable, ElTableColumn, ElTabs, ElTabPane,
  ElLoading,
} from 'element-plus'
import 'element-plus/dist/index.css'

const vLoading = ElLoading.directive

type Tab = 'management' | 'requests' | 'logs'
interface CreditRequest {
  id: string; userId: string; displayName: string; userNumber: string
  type: 'tokens' | 'unlimited'; requestedTokens: number; approvedTokens: number
  reason: string; status: string; reviewNote: string; reviewer: string; createdAt: string; reviewedAt: string
}
interface CallLog {
  requestId: string; displayName: string | null; userNumber: string | null; keyName: string; maskedKey: string
  model: string; endpoint: string; stream: boolean; httpStatus: number; upstreamStatus: number
  status: string; errorCode: string; errorMessage: string; detail: string | null
  inputTokens: number; outputTokens: number; estimated: boolean; durationMs: number; createdAt: string
}
const props = withDefaults(defineProps<{ mode: 'user' | 'admin'; initialTab?: Tab }>(), { initialTab: 'requests' })
const emit = defineEmits<{ 'update:tab': [tab: 'requests' | 'logs'] }>()
const admin = computed(() => props.mode === 'admin')
const prefix = computed(() => admin.value ? '/api/admin/api' : '/api/developer')
const tab = ref<Tab>(props.initialTab)
const requests = ref<CreditRequest[]>([])
const logs = ref<CallLog[]>([])
const pendingCount = ref(0)
const pendingKnown = ref(false)
const pendingError = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const mutationError = ref('')
const page = ref(0)
const size = ref(20)
const total = ref(0)
const filters = reactive({ status: 'all', q: '', model: '', from: '' as string | null, to: '' as string | null })
const form = reactive({ type: 'tokens' as 'tokens' | 'unlimited', amount: 100000, reason: '' })
const review = ref<CreditRequest | null>(null)
const decision = reactive({ approved: true, approvedTokens: 0, note: '' })
const selectedLog = ref<CallLog | null>(null)
let generation = 0
const date = (value: string) => value ? new Date(value).toLocaleString('zh-CN') : '-'
const labels: Record<string, string> = { pending: '待审批', approved: '已批准', rejected: '已拒绝', success: '成功', failed: '失败' }
async function request(url: string, body?: unknown) {
  const response = await fetch(url, body === undefined ? undefined : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(payload?.error?.message || '请求失败，请重试')
  return payload
}
async function load() {
  const current = ++generation
  if (tab.value === 'management') { loading.value = false; return }
  if (tab.value === 'logs' && filters.from && filters.to && filters.from > filters.to) {
    loading.value = false; error.value = '开始日期不能晚于结束日期'; return
  }
  loading.value = true; error.value = ''
  const params = new URLSearchParams({ page: String(page.value), size: String(size.value), status: filters.status })
  if (tab.value === 'logs') for (const key of ['q', 'model', 'from', 'to'] as const) {
    const value = filters[key]?.trim()
    if (value) params.set(key, value)
  }
  try {
    const payload = await request(`${prefix.value}/${tab.value === 'requests' ? 'credit-requests' : 'call-logs'}?${params}`)
    if (current !== generation) return
    total.value = payload.total
    if (tab.value === 'requests') {
      requests.value = payload.items; pendingCount.value = payload.pendingCount; pendingKnown.value = true; pendingError.value = ''
    } else logs.value = payload.items
  } catch (e) {
    if (current === generation) {
      error.value = e instanceof Error ? e.message : '加载失败'
      if (tab.value === 'requests') pendingKnown.value = false
    }
  }
  finally { if (current === generation) loading.value = false }
}
async function loadPending() {
  try {
    const payload = await request(`${prefix.value}/credit-requests?page=0&size=1&status=pending`)
    pendingCount.value = payload.pendingCount; pendingKnown.value = true; pendingError.value = ''
  } catch { pendingKnown.value = false; pendingError.value = '待审批数量刷新失败，请点击刷新重试' }
}
async function refresh() {
  await load()
  if (tab.value !== 'requests') await loadPending()
}
function search() { page.value = 0; void load() }
watch(tab, () => {
  filters.status = 'all'; page.value = 0; total.value = 0
  if (tab.value !== 'management') emit('update:tab', tab.value)
  void load()
})
onMounted(() => { void load(); if (tab.value !== 'requests') void loadPending() })
onBeforeUnmount(() => { generation++ })
async function submit() {
  if (saving.value || !pendingKnown.value || pendingCount.value > 0) return
  mutationError.value = ''
  if (!form.reason.trim() || form.reason.trim().length > 1000 || (form.type === 'tokens' && (!Number.isSafeInteger(form.amount) || form.amount <= 0))) {
    mutationError.value = '请填写有效的整数额度和申请原因（1–1000 字）'; return
  }
  saving.value = true
  try {
    await request(`${prefix.value}/credit-requests`, { type: form.type, amount: form.type === 'unlimited' ? 0 : form.amount, reason: form.reason.trim() })
    form.reason = ''; pendingCount.value++; await load()
  } catch (e) { mutationError.value = e instanceof Error ? e.message : '提交失败'; await loadPending() }
  finally { saving.value = false }
}
function openReview(row: CreditRequest) {
  review.value = row; decision.approved = true; decision.approvedTokens = row.requestedTokens; decision.note = ''; mutationError.value = ''
}
async function saveReview() {
  if (!review.value || saving.value) return
  mutationError.value = ''
  if (decision.note.length > 1000 || (decision.approved && review.value.type === 'tokens' && (!Number.isSafeInteger(decision.approvedTokens) || decision.approvedTokens <= 0))) {
    mutationError.value = '批准额度必须为正整数，备注不超过 1000 字'; return
  }
  saving.value = true
  try {
    await request(`${prefix.value}/credit-requests/${encodeURIComponent(review.value.id)}/review`, {
      approved: decision.approved, approvedTokens: decision.approved && review.value.type === 'tokens' ? decision.approvedTokens : 0, note: decision.note.trim(),
    })
    review.value = null; await load()
  } catch (e) { mutationError.value = e instanceof Error ? e.message : '审批失败' }
  finally { saving.value = false }
}
</script>

<template>
  <section class="api-activity">
    <el-tabs v-model="tab">
      <el-tab-pane v-if="admin" label="模型与密钥" name="management" />
      <el-tab-pane name="requests"><template #label>额度申请 <el-badge v-if="pendingKnown && pendingCount" :value="pendingCount" /></template></el-tab-pane>
      <el-tab-pane label="调用记录" name="logs" />
    </el-tabs>
    <slot v-if="tab === 'management'" name="management" />
    <template v-else>
      <el-form v-if="tab === 'requests' && !admin" label-position="top" class="request-form" @submit.prevent="submit">
        <el-form-item label="申请类型"><el-select v-model="form.type" aria-label="申请类型"><el-option label="追加 Tokens" value="tokens" /><el-option label="无限制" value="unlimited" /></el-select></el-form-item>
        <el-form-item v-if="form.type === 'tokens'" label="追加 Tokens"><el-input-number v-model="form.amount" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" /></el-form-item>
        <el-form-item label="申请原因" class="reason"><el-input v-model="form.reason" type="textarea" maxlength="1000" show-word-limit :rows="2" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="saving" :disabled="loading || !pendingKnown || pendingCount > 0">提交申请</el-button>
        <span v-if="pendingKnown && pendingCount">已有待审批申请</span>
        <el-alert v-if="mutationError" :title="mutationError" type="error" :closable="false" />
      </el-form>
      <el-form inline class="filters" @submit.prevent="search">
        <el-form-item label="状态"><el-select v-model="filters.status" aria-label="状态"><el-option label="全部" value="all" /><el-option v-for="s in tab === 'logs' ? ['success', 'failed'] : ['pending', 'approved', 'rejected']" :key="s" :label="labels[s]" :value="s" /></el-select></el-form-item>
        <template v-if="tab === 'logs'">
          <el-form-item label="搜索"><el-input v-model="filters.q" clearable placeholder="姓名 / 工号 / 请求 ID" /></el-form-item>
          <el-form-item label="模型"><el-input v-model="filters.model" clearable placeholder="精确模型 ID" /></el-form-item>
          <el-form-item label="开始日期"><el-date-picker v-model="filters.from" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="filters.to" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <el-button native-type="submit" :loading="loading">查询</el-button>
        <el-button title="刷新" aria-label="刷新" :disabled="loading" @click="refresh"><RefreshCw :size="16" /></el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="pendingError" :title="pendingError" type="error" :closable="false" />
      <div v-loading="loading" class="table-scroll" :aria-busy="loading">
        <el-table v-if="tab === 'requests'" :data="error ? [] : requests" size="small" empty-text="暂无额度申请" row-key="id">
          <el-table-column v-if="admin" prop="displayName" label="用户" min-width="100" /><el-table-column v-if="admin" prop="userNumber" label="工号" min-width="100" />
          <el-table-column label="申请额度" min-width="120"><template #default="{ row }">{{ row.type === 'unlimited' ? '无限制' : row.requestedTokens.toLocaleString() }}</template></el-table-column>
          <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="90"><template #default="{ row }">{{ labels[row.status] || row.status }}</template></el-table-column>
          <el-table-column label="批准额度" min-width="110"><template #default="{ row }">{{ row.status === 'approved' ? (row.type === 'unlimited' ? '无限制' : row.approvedTokens?.toLocaleString()) : '-' }}</template></el-table-column>
          <el-table-column prop="reviewNote" label="审批备注" min-width="160" show-overflow-tooltip /><el-table-column prop="reviewer" label="审批人" min-width="100" />
          <el-table-column label="申请时间" min-width="170"><template #default="{ row }">{{ date(row.createdAt) }}</template></el-table-column>
          <el-table-column label="审批时间" min-width="170"><template #default="{ row }">{{ date(row.reviewedAt) }}</template></el-table-column>
          <el-table-column v-if="admin" label="操作" width="80" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'pending'" link type="primary" @click="openReview(row as CreditRequest)">审批</el-button></template></el-table-column>
        </el-table>
        <el-table v-else :data="error ? [] : logs" size="small" empty-text="暂无调用记录" row-key="requestId">
          <el-table-column prop="requestId" label="请求 ID" min-width="160" show-overflow-tooltip />
          <el-table-column v-if="admin" prop="displayName" label="用户" min-width="100"><template #default="{ row }">{{ row.displayName || row.userNumber || '未识别用户' }}</template></el-table-column>
          <el-table-column v-if="admin" prop="userNumber" label="工号" min-width="100" />
          <el-table-column prop="keyName" label="Key 名称" min-width="110" /><el-table-column prop="maskedKey" label="脱敏 Key" min-width="130" />
          <el-table-column prop="model" label="模型" min-width="140" /><el-table-column label="状态" width="80"><template #default="{ row }">{{ labels[row.status] }}</template></el-table-column>
          <el-table-column prop="httpStatus" label="HTTP" width="70" /><el-table-column prop="inputTokens" label="输入 Tokens" min-width="100" /><el-table-column prop="outputTokens" label="输出 Tokens" min-width="100" />
          <el-table-column label="计量" width="80"><template #default="{ row }">{{ row.estimated ? '估算' : '实际' }}</template></el-table-column>
          <el-table-column prop="durationMs" label="耗时 ms" width="100" /><el-table-column label="时间" min-width="170"><template #default="{ row }">{{ date(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="70" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="selectedLog = row as CallLog">详情</el-button></template></el-table-column>
        </el-table>
      </div>
      <div class="pagination"><el-pagination :current-page="page + 1" :page-size="size" :total="total" layout="total, prev, pager, next" :pager-count="5" :disabled="loading" @current-change="page = $event - 1; load()" /></div>
    </template>
    <el-dialog :model-value="!!review" title="审批额度申请" width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="review = null">
      <template v-if="review">
        <p>{{ review.displayName }} · {{ review.userNumber }}</p><p class="wrapped">{{ review.reason }}</p>
        <el-form label-position="top" @submit.prevent="saveReview">
          <el-form-item label="审批结果"><el-radio-group v-model="decision.approved"><el-radio :value="true">批准</el-radio><el-radio :value="false">拒绝</el-radio></el-radio-group></el-form-item>
          <el-form-item v-if="decision.approved && review.type === 'tokens'" label="批准 Tokens"><el-input-number v-model="decision.approvedTokens" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" /></el-form-item>
          <p v-if="decision.approved && review.type === 'unlimited'">批准后额度为无限制</p>
          <el-form-item label="审批备注"><el-input v-model="decision.note" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
          <el-alert v-if="mutationError" :title="mutationError" type="error" :closable="false" />
          <el-button native-type="submit" type="primary" :loading="saving">确认审批</el-button><el-button :disabled="saving" @click="review = null">取消</el-button>
        </el-form>
      </template>
    </el-dialog>
    <el-dialog :model-value="!!selectedLog" title="调用详情" width="min(720px, 94vw)" @close="selectedLog = null">
      <el-descriptions v-if="selectedLog" :column="1" border class="wrapped">
        <el-descriptions-item label="请求 ID">{{ selectedLog.requestId }}</el-descriptions-item>
        <el-descriptions-item label="端点">{{ selectedLog.endpoint }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ selectedLog.model }}</el-descriptions-item>
        <el-descriptions-item label="流式">{{ selectedLog.stream ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="HTTP / 上游状态">{{ selectedLog.httpStatus ?? '-' }} / {{ selectedLog.upstreamStatus ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误码">{{ selectedLog.errorCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">{{ selectedLog.errorMessage || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="admin" label="诊断详情"><pre class="wrapped">{{ selectedLog.detail || '-' }}</pre></el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </section>
</template>

<style scoped>
.api-activity { min-width: 0; width: 100%; }
.request-form { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 20px; }
.request-form .reason { flex: 1 1 280px; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; margin: 12px 0; }
.filters :deep(.el-form-item) { margin: 0; }
.filters :deep(.el-select) { width: 120px; }
.request-form :deep(.el-select) { width: 160px; }
.filters :deep(.el-date-editor) { width: 150px; }
.table-scroll { overflow-x: auto; width: 100%; }
.table-scroll :deep(.el-table) { min-width: 900px; }
.pagination { overflow-x: auto; margin-top: 14px; display: flex; justify-content: flex-end; }
.wrapped { white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
@media (max-width: 600px) { .pagination { justify-content: flex-start; } .request-form { align-items: stretch; } }
</style>
