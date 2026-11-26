<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SystemSettingsPanel from './SystemSettingsPanel.vue'
interface Policy { defaultApiTokens: number; dailyChatRequests: number; concurrentRequests: number; maintenance: boolean; maintenanceMessage: string }
const form = ref<Policy>({ defaultApiTokens: 10000000, dailyChatRequests: 0, concurrentRequests: 0, maintenance: false, maintenanceMessage: '系统维护中，请稍后再试。' })
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
async function load() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    const response = await fetch('/api/admin/runtime-policy', { signal: AbortSignal.timeout(15000) })
    if (response.status === 401) throw new Error('管理员登录已失效，请重新登录后台后刷新页面')
    if (response.status === 404) throw new Error('后端尚未更新，缺少运行策略接口。更新并重启后端后，点击“重新加载”。')
    if (!response.ok) throw new Error('运行策略加载失败，请点击“重新加载”重试')
    form.value = await response.json(); loaded.value = true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { busy.value = false }
}
async function save() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    const response = await fetch('/api/admin/runtime-policy', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) })
    if (!response.ok) throw new Error('保存失败，请检查填写内容并重试')
    form.value = await response.json()
    message.value = '已保存，新请求立即应用。已有 API 余额和正在执行的请求保持不变。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <form class="runtime-policy" @submit.prevent="save">
    <header><div><h2>全局运行策略</h2><p>配置额度、访问限额与维护规则，保存后应用于新请求。</p></div><button type="button" :disabled="busy" @click="load">重新加载</button></header>
    <p v-if="!loaded && busy" role="status">正在加载当前设置，加载完成后即可编辑…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <fieldset :disabled="busy || !loaded">
      <div class="policy-grid">
        <section class="policy-card"><span class="policy-icon">额度</span><h3>新用户默认 API 额度</h3><label>初始 Token 数量<input v-model.number="form.defaultApiTokens" type="number" min="0" max="9007199254740991" step="1" required /></label><p>仅在新用户首次登录时发放。0 表示不发放；已有用户余额不调整。</p></section>
        <section class="policy-card"><span class="policy-icon">限额</span><h3>网页对话每日限额</h3><label>每用户每日请求次数<input v-model.number="form.dailyChatRequests" type="number" min="0" max="1000000" step="1" required /></label><p>0 表示不限。按北京时间每天重置，受理的对话、编辑后生成和重新生成均计数，失败也计数；API 调用不计入此限额。</p></section>
        <section class="policy-card"><span class="policy-icon">并发</span><h3>每用户并发控制</h3><label>同时执行的请求数<input v-model.number="form.concurrentRequests" type="number" min="0" max="100" step="1" required /></label><p>0 表示不限。网页对话与 API 推理共享上限，按用户汇总多个 API Key；超限返回提示，完成后释放名额。</p></section>
        <section class="policy-card" :class="{ maintenance: form.maintenance }"><span class="policy-icon">维护</span><h3>维护模式</h3><label class="maintenance-toggle"><input v-model="form.maintenance" type="checkbox" />{{ form.maintenance ? '保存后暂停新请求' : '当前配置：正常服务' }}</label><label>维护提示<textarea v-model="form.maintenanceMessage" rows="3" required maxlength="500"></textarea></label><p>暂停新的网页对话、API 推理、语音和附件上传。历史查看、下载及后台管理继续可用。</p></section>
      </div>
    </fieldset>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <footer><span>默认不限额、不限并发、不开启维护。日请求计数自本功能上线起记录。</span><button class="primary" type="submit" :disabled="busy || !loaded">{{ busy ? '处理中…' : '保存运行策略' }}</button></footer>
  </form>
  <details class="secondary-settings"><summary>基础信息、文件限制与部署信息</summary><SystemSettingsPanel /></details>
</template>
<style scoped>
.runtime-policy { padding: 24px; background: #fff; border: 1px solid #e2e8e5; border-radius: 12px; }header, footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; }h2 { margin: 0; font-size: 20px; color: #203e33; }header p { color: #7b8380; font-size: 13px; }fieldset { border: 0; margin: 22px 0; padding: 0; }.policy-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px; }.policy-card { padding: 22px; background: #fbfcfb; border: 1px solid #e3eae6; border-radius: 10px; }.policy-card h3 { margin: 16px 0; font-size: 16px; color: #2e463c; }.policy-icon { display: inline-block; padding: 6px 10px; background: #e7f1eb; color: #176f55; border-radius: 6px; font-size: 12px; font-weight: 600; }label { display: grid; gap: 9px; color: #46554e; font-size: 13px; }input:not([type='checkbox']),textarea { box-sizing: border-box; width: 100%; padding: 11px 12px; background: #fff; border: 1px solid #d6e0da; border-radius: 7px; font: inherit; }.policy-card p,footer span { color: #7b8380; font-size: 12px; line-height: 1.8; }.maintenance-toggle { display: flex; align-items: center; margin-bottom: 16px; }.maintenance { border-color: #e0bb82; background: #fffbf3; }button { padding: 9px 16px; border: 1px solid #d8ddda; border-radius: 7px; background: #fff; white-space: nowrap; }.primary { background: #176f55; border-color: #176f55; color: #fff; }button:disabled { opacity: .6; cursor: wait; }.error { color: #a34b42; }.success { color: #176f55; }.secondary-settings { margin-top: 24px; }.secondary-settings summary { padding: 14px 0; color: #69766e; cursor: pointer; font-size: 13px; }@media(max-width:850px) { .policy-grid { grid-template-columns:1fr; }header,footer { align-items:flex-start;flex-wrap:wrap; } }
</style>
