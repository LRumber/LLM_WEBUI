<script setup lang="ts">
import { onMounted, ref } from 'vue'
const statusError = ref('')
const checking = ref(false)
const runtime = ref<{ version: string; checkedAt: string; components: { name: string; status: string }[] } | null>(null)
const origin = window.location.origin
const labels: Record<string, string> = { UP: '正常', DOWN: '异常', TIMEOUT: '检测超时', NOT_CONFIGURED: '未配置' }
async function check() {
  checking.value = true; statusError.value = ''
  try {
    const response = await fetch('/api/admin/runtime-status')
    if (!response.ok) throw new Error('运行状态检测失败，请重试')
    runtime.value = await response.json()
  } catch (cause) { statusError.value = cause instanceof Error ? cause.message : '检测失败' }
  finally { checking.value = false }
}
onMounted(check)
</script>
<template>
    <section class="settings-card">
      <header><div><h2>运行状态</h2><p>实际连接检测，单次检测最多约 3 秒。</p></div><button type="button" :disabled="checking" @click="check">{{ checking ? '检测中…' : '刷新检测' }}</button></header>
      <p v-if="statusError" class="error" role="alert">{{ statusError }}</p>
      <div v-if="runtime" class="status-grid"><article v-for="item in runtime.components" :key="item.name"><strong>{{ item.name }}</strong><span :class="item.status === 'UP' ? 'success' : 'error'">{{ labels[item.status] || '未知' }}</span></article></div>
      <p v-else-if="checking">正在检测…</p>
      <small v-if="runtime">版本：{{ runtime.version }} · 检测时间：{{ new Date(runtime.checkedAt).toLocaleString('zh-CN') }}{{ checking ? '（正在刷新）' : '' }}</small>
      <p class="hint">MinIO 检测存储桶是否可访问；异常也可能表示存储桶尚未创建。刷新失败时保留上次检测时间。</p>
    </section>
</template>
<style scoped>
.system-settings { display: grid; gap: 20px; }.settings-card { padding: 22px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }
header, footer { display: flex; align-items: center; justify-content: space-between; gap: 15px; }h2 { margin: 0 0 8px; font-size: 16px; }p { font-size: 13px; }header p, small { color: #7b8380; line-height: 1.6; font-size: 12px; }
fieldset { display: grid; gap: 18px; border: 0; margin: 20px 0; padding: 0; }label { display: grid; gap: 8px; font-size: 13px; color: #46504c; }input, textarea { box-sizing: border-box; width: 100%; padding: 10px; border: 1px solid #d8ddda; border-radius: 7px; font: inherit; }button { padding: 8px 14px; border: 1px solid #d8ddda; border-radius: 7px; background: #fff; white-space: nowrap; }.primary { background: #176f55; color: #fff; border-color: #176f55; }button:disabled { opacity: .55; cursor: wait; }.error { color: #a34b42; }.success { color: #176f55; }.status-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(140px,1fr)); gap: 12px; margin: 20px 0; }.status-grid article { display: grid; gap: 10px; padding: 15px; border: 1px solid #e4e7e5; border-radius: 7px; font-size: 13px; }.hint { color: #7b8380; font-size: 12px; }small { overflow-wrap: anywhere; }
</style>
