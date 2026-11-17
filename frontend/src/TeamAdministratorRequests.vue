<script setup lang="ts">
import { onMounted, ref } from 'vue'

interface Application {
  id: string; displayName: string; userNumber: string | null; reason: string
  status: string; reviewNote: string | null; reviewer: string | null; createdAt: string; reviewedAt: string | null
}
const emit = defineEmits<{ reviewed: [] }>()
const applications = ref<Application[]>([])
const page = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const selected = ref<Application | null>(null)
const approved = ref(true)
const note = ref('')
const labels: Record<string, string> = { pending: '待审批', approved: '已通过', rejected: '已拒绝' }
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '-'

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`/api/admin/team-administrator-requests?page=${target}`)
    if (!response.ok) throw new Error('申请列表加载失败，请重试')
    applications.value = await response.json()
    page.value = target
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '申请列表加载失败，请重试'
  } finally { loading.value = false }
}

function openReview(application: Application, decision: boolean) {
  selected.value = application
  approved.value = decision
  note.value = ''
  error.value = ''
}

async function review() {
  if (!selected.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const response = await fetch(`/api/admin/team-administrator-requests/${selected.value.id}/review`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved: approved.value, note: note.value }),
    })
    if (!response.ok) {
      const detail = await response.json().catch(() => null)
      throw new Error(detail?.message || '审批失败，请重试')
    }
    selected.value = null
    await load(0)
    emit('reviewed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '审批失败，请重试' }
  finally { saving.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="admin-panel team-request-panel">
    <div class="panel-heading"><div><h2>团队管理员申请</h2><p>审批通过后授予创建团队权限</p></div><button type="button" :disabled="loading" @click="load()">刷新</button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="table-wrap"><table>
      <thead><tr><th>申请人</th><th>工号</th><th>申请理由</th><th>申请时间</th><th>状态</th><th>审批意见</th><th>审批人</th><th>审批时间</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="application in applications" :key="application.id">
          <td>{{ application.displayName }}</td><td>{{ application.userNumber || '-' }}</td><td class="request-text">{{ application.reason }}</td><td>{{ date(application.createdAt) }}</td>
          <td>{{ labels[application.status] }}</td><td class="request-text">{{ application.reviewNote || '-' }}</td><td>{{ application.reviewer || '-' }}</td><td>{{ date(application.reviewedAt) }}</td>
          <td><template v-if="application.status === 'pending'"><button type="button" class="table-action" :disabled="saving" @click="openReview(application, true)">通过</button><button type="button" class="table-action" :disabled="saving" @click="openReview(application, false)">拒绝</button></template></td>
        </tr>
        <tr v-if="!applications.length"><td colspan="9" class="empty-table">{{ loading ? '加载中…' : '暂无申请' }}</td></tr>
      </tbody>
    </table></div>
    <div class="pagination-row"><button type="button" :disabled="page === 0 || loading || saving" @click="load(page - 1)">上一页</button><span>第 {{ page + 1 }} 页</span><button type="button" :disabled="applications.length < 20 || loading || saving" @click="load(page + 1)">下一页</button></div>
    <div v-if="selected" class="team-dialog-backdrop">
      <form class="team-dialog" @submit.prevent="review">
        <header><div><h2>{{ approved ? '通过' : '拒绝' }}团队管理员申请</h2><p>申请人：{{ selected.displayName }}</p></div></header>
        <label>审批意见<textarea v-model="note" maxlength="1000" rows="4" :disabled="saving"></textarea></label>
        <p v-if="error" role="alert">{{ error }}</p>
        <footer><button type="button" :disabled="saving" @click="selected = null">取消</button><button type="submit" class="primary" :disabled="saving">{{ saving ? '处理中…' : '确认' }}</button></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.team-request-panel { margin-bottom: 20px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 15px; }
.panel-heading h2 { margin: 0; font-size: 15px; }
.panel-heading p { margin: 5px 0 0; color: #7b8380; font-size: 12px; }
.panel-heading button, .pagination-row button { padding: 7px 12px; border: 1px solid #d9dddb; border-radius: 7px; background: #fff; color: #46504c; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; }
th, td { padding: 12px 10px; border-bottom: 1px solid #edf0ee; white-space: nowrap; }
th { color: #7b8380; font-size: 11px; font-weight: 500; }
.table-action { color: #257657; background: transparent; border: 0; padding: 4px 6px; }
.empty-table { text-align: center; color: #7b8380; }
.pagination-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 15px; font-size: 12px; }
.request-text { min-width: 180px; max-width: 320px; white-space: pre-wrap; overflow-wrap: anywhere; }
[role='alert'] { color: #a34b42; }
</style>
