<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElButton, ElDialog, ElInput, ElAlert, ElTable, ElTableColumn, ElPagination } from 'element-plus'
const props = defineProps<{ admin?: boolean }>()
const open = ref(false)
const content = ref('')
const busy = ref(false)
const error = ref('')
const success = ref('')
const items = ref<any[]>([])
const page = ref(1)
const total = ref(0)
async function load() {
  busy.value = true; error.value = ''
  try {
    const response = await fetch(`/api/admin/feedback?page=${page.value - 1}`)
    if (!response.ok) throw new Error('加载反馈失败，请确认管理员登录状态后重试。')
    const result = await response.json(); items.value = result.items; total.value = result.total
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function send() {
  if (busy.value || !content.value.trim()) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const response = await fetch('/api/developer/feedback', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content: content.value.trim() }) })
    if (!response.ok) throw new Error(response.status === 401 ? '登录已失效，请重新登录后提交。' : '提交失败，请稍后重试。')
    content.value = ''; success.value = '反馈已提交，感谢您的意见。'
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
onMounted(() => { if (props.admin) void load() })
</script>
<template>
  <section v-if="admin" class="feedback-admin">
    <h2>意见反馈</h2>
    <ElButton :loading="busy" @click="load">刷新</ElButton>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElTable :data="items" empty-text="暂无意见反馈">
      <ElTableColumn prop="displayName" label="姓名" width="120" />
      <ElTableColumn prop="userNumber" label="工号" width="110" />
      <ElTableColumn label="反馈内容" min-width="250"><template #default="{ row }"><div class="feedback-text">{{ row.content }}</div></template></ElTableColumn>
      <ElTableColumn label="时间" width="185"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString('zh-CN') }}</template></ElTableColumn>
    </ElTable>
    <ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
  </section>
  <template v-else>
    <button class="nav-item feedback-entry" type="button" @click="open = true; success = ''; error = ''">意见反馈</button>
    <ElDialog v-model="open" title="意见反馈" width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <form @submit.prevent="send">
        <ElInput v-model="content" type="textarea" :rows="6" maxlength="4000" show-word-limit placeholder="请描述遇到的问题或您的建议，请勿填写密码、API Key 等敏感信息。" :disabled="busy" />
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <ElAlert v-if="success" :title="success" type="success" :closable="false" />
        <div class="feedback-actions"><ElButton :disabled="busy" @click="open = false">关闭</ElButton><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!content.trim()">提交反馈</ElButton></div>
      </form>
    </ElDialog>
  </template>
</template>
<style scoped>
.feedback-entry { width: 100%; cursor: pointer; background: transparent; border: 0; text-align: left; font: inherit; }
.feedback-actions { display: flex; justify-content: flex-end; margin-top: 20px; }
.feedback-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.feedback-admin { min-width: 0; overflow-x: auto; }
</style>
