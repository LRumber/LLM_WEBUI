<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { defaultPlatformSettings, type PlatformSettings } from './platformSettings'
const form = ref<PlatformSettings>({ ...defaultPlatformSettings })
const ceiling = ref(0)
const supported = ref('')
const sizeMb = ref(100)
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const origin = window.location.origin
async function load() {
  loading.value = true; error.value = ''; message.value = ''
  try {
    const response = await fetch('/api/admin/platform-settings')
    if (!response.ok) throw new Error('设置加载失败，请重试')
    const data = await response.json()
    form.value = data.settings; ceiling.value = data.uploadCeiling; supported.value = data.supportedExtensions
    sizeMb.value = data.settings.maxUploadBytes / 1048576; loaded.value = true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '设置加载失败' }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || !loaded.value) return
  saving.value = true; error.value = ''; message.value = ''
  try {
    const response = await fetch('/api/admin/platform-settings', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...form.value, maxUploadBytes: Math.floor(sizeMb.value * 1048576) }),
    })
    const data = await response.json().catch(() => null)
    if (!response.ok) throw new Error(data?.message || '保存失败，请检查填写内容')
    form.value = data; sizeMb.value = data.maxUploadBytes / 1048576
    message.value = '已保存。基础信息刷新页面后生效，上传限制立即应用于新上传；无需重启。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="system-settings">
    <form class="settings-card" @submit.prevent="save">
      <header><div><h2>基础设置</h2><p>基础信息刷新页面生效，文件限制应用于新上传。</p></div><button type="button" :disabled="saving || loading" @click="load">重新加载</button></header>
      <fieldset :disabled="!loaded || loading || saving">
        <label>平台名称<input v-model="form.platformName" required maxlength="80" /></label>
        <label>登录页说明<textarea v-model="form.loginDescription" required maxlength="500" rows="3"></textarea></label>
        <label>联系管理员信息<input v-model="form.adminContact" maxlength="300" placeholder="例如：联系信息中心，分机 1234" /><small>填写后展示在登录页，留空则隐藏。</small></label>
        <h2>文件设置</h2>
        <label>单文件上传上限（MB）<input v-model.number="sizeMb" type="number" :min="1 / 1048576" step="any" :max="ceiling / 1048576" required /><small>部署允许的上限为 {{ Number((ceiling / 1048576).toFixed(2)) }} MB；反向代理还可能有更低限制。</small></label>
        <label>允许上传的文件扩展名<textarea v-model="form.allowedExtensions" required maxlength="1000" rows="3"></textarea><small>以英文逗号分隔，可从以下支持类型中选择：{{ supported }}</small></label>
      </fieldset>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="success" role="status">{{ message }}</p>
      <footer><small>调整上传限制不影响已有文件的下载。</small><button class="primary" type="submit" :disabled="!loaded || loading || saving">{{ saving ? '保存中…' : '保存设置' }}</button></footer>
    </form>
    <section class="settings-card"><h2>部署信息</h2><p>当前访问地址：{{ origin }}</p><p>当前连接：{{ origin.startsWith('https:') ? 'HTTPS' : 'HTTP' }}</p><small>域名、端口和证书由部署配置管理；默认模型在“模型管理”中设置。</small></section>
  </div>
</template>
<style scoped>
.system-settings { display: grid; gap: 20px; }.settings-card { padding: 22px; background: #fff; border: 1px solid #e4e7e5; border-radius: 10px; }
header, footer { display: flex; align-items: center; justify-content: space-between; gap: 15px; }h2 { margin: 0 0 8px; font-size: 16px; }p { font-size: 13px; }header p, small { color: #7b8380; line-height: 1.6; font-size: 12px; }
fieldset { display: grid; gap: 18px; border: 0; margin: 20px 0; padding: 0; }label { display: grid; gap: 8px; font-size: 13px; color: #46504c; }input, textarea { box-sizing: border-box; width: 100%; padding: 10px; border: 1px solid #d8ddda; border-radius: 7px; font: inherit; }button { padding: 8px 14px; border: 1px solid #d8ddda; border-radius: 7px; background: #fff; white-space: nowrap; }.primary { background: #176f55; color: #fff; border-color: #176f55; }button:disabled { opacity: .55; cursor: wait; }.error { color: #a34b42; }.success { color: #176f55; }.status-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(140px,1fr)); gap: 12px; margin: 20px 0; }.status-grid article { display: grid; gap: 10px; padding: 15px; border: 1px solid #e4e7e5; border-radius: 7px; font-size: 13px; }.hint { color: #7b8380; font-size: 12px; }small { overflow-wrap: anywhere; }
</style>
