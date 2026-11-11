<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ApiActivityPanel from './ApiActivityPanel.vue'
import { ElButton } from 'element-plus'
import { ArrowLeft, BarChart3, Check, Code2, Copy, KeyRound, RefreshCw, Terminal } from 'lucide-vue-next'

interface ApiKeySummary {
  id: string
  name: string
  maskedKey: string
  enabled: boolean
  createdAt: string
  lastUsedAt?: string | null
}

interface ApiModelSummary {
  key: string
  displayName: string
  category: string
  capabilities: string[]
  contextWindow?: number | null
  available?: boolean
}

interface ApiUsageDay {
  date: string
  requestCount: number
  inputTokens: number
  outputTokens: number
}

interface ApiDeveloperSummary {
  baseUrl: string
  quota: {
    totalQuota: number
    usedTokens: number
    remainingTokens: number
    requestCount: number
    unlimited: boolean
    reservedTokens: number
    todayTokens: number
    monthTokens: number
  }
  models: ApiModelSummary[]
  dailyUsage: ApiUsageDay[]
}

const summary = ref<ApiDeveloperSummary | null>(null)
const keys = ref<ApiKeySummary[]>([])
const keyName = ref('')
const newSecret = ref('')
const loading = ref(false)
const copied = ref('')
const activeSection = ref<'usage' | 'keys' | 'docs' | 'requests' | 'logs'>('usage')

const formatNumber = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const maxDailyTokens = computed(() => Math.max(...(summary.value?.dailyUsage ?? []).map((day) => day.inputTokens + day.outputTokens), 1))
const chatModels = computed(() => summary.value?.models.filter((model) => model.capabilities.includes('chat')) ?? [])
const embeddingModels = computed(() => summary.value?.models.filter((model) => model.capabilities.includes('embedding')) ?? [])
const rerankModels = computed(() => summary.value?.models.filter((model) => model.capabilities.includes('rerank')) ?? [])
const speechModels = computed(() => summary.value?.models.filter((model) => model.capabilities.includes('speech_to_text')) ?? [])

const load = async () => {
  loading.value = true
  try {
    const [summaryResponse, keysResponse] = await Promise.all([
      fetch('/api/developer/summary'),
      fetch('/api/developer/keys'),
    ])
    if (summaryResponse.ok) summary.value = await summaryResponse.json()
    if (keysResponse.ok) keys.value = await keysResponse.json()
  } finally {
    loading.value = false
  }
}

const createKey = async () => {
  const name = keyName.value.trim()
  if (!name) return window.alert('请先填写 Key 名称')
  const response = await fetch('/api/developer/keys', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!response.ok) return window.alert('API Key 创建失败')
  const payload = await response.json()
  newSecret.value = payload.secret
  await load()
}

const resetKey = async (key: ApiKeySummary) => {
  if (!window.confirm(`确定重置“${key.name}”吗？旧 Key 会立即失效。`)) return
  const response = await fetch(`/api/developer/keys/${key.id}/reset`, { method: 'POST' })
  if (!response.ok) return window.alert('API Key 重置失败')
  const payload = await response.json()
  newSecret.value = payload.secret
  await load()
}

const copyText = async (id: string, text: string) => {
  await navigator.clipboard.writeText(text)
  copied.value = id
  window.setTimeout(() => {
    if (copied.value === id) copied.value = ''
  }, 1600)
}

onMounted(load)
</script>

<template>
  <main class="api-platform">
    <aside class="api-platform-sidebar">
      <a class="api-platform-back" href="/app"><ArrowLeft :size="17" />返回对话</a>
      <div class="api-platform-brand"><strong>SITP AI</strong><span>API 开发平台</span></div>
      <button :class="{ active: activeSection === 'usage' }" type="button" @click="activeSection = 'usage'"><BarChart3 :size="18" />用量信息</button>
      <button :class="{ active: activeSection === 'keys' }" type="button" @click="activeSection = 'keys'"><KeyRound :size="18" />API Keys</button>
      <button :class="{ active: activeSection === 'docs' }" type="button" @click="activeSection = 'docs'"><Code2 :size="18" />接口文档</button>
      <button :class="{ active: activeSection === 'requests' }" type="button" @click="activeSection = 'requests'"><KeyRound :size="18" />额度申请</button>
      <button :class="{ active: activeSection === 'logs' }" type="button" @click="activeSection = 'logs'"><Terminal :size="18" />调用记录</button>
    </aside>

    <section class="api-platform-content" :class="{ loading }">
      <section v-if="activeSection === 'usage'" class="api-section">
        <header><h1>用量信息</h1><p>API 调用独立统计，不计入网页对话用量。</p></header>
        <el-button @click="activeSection = 'requests'">申请额度</el-button>
        <div class="api-metrics">
          <article><span>今日用量</span><strong>{{ formatNumber(summary?.quota.todayTokens ?? 0) }}</strong><small>tokens</small></article>
          <article><span>累计已用</span><strong>{{ formatNumber(summary?.quota.usedTokens ?? 0) }}</strong><small>tokens</small></article>
          <article><span>剩余额度</span><strong>{{ summary?.quota.unlimited ? '无限制' : formatNumber(summary?.quota.remainingTokens ?? 0) }}</strong><small v-if="!summary?.quota.unlimited">tokens</small></article>
          <article><span>本月用量</span><strong>{{ formatNumber(summary?.quota.monthTokens ?? 0) }}</strong><small>tokens</small></article>
        </div>
        <p v-if="summary?.quota.reservedTokens">正在处理的请求预占 {{ formatNumber(summary.quota.reservedTokens) }} tokens，结束后按用量结算。</p>
        <p>累计请求 {{ formatNumber(summary?.quota.requestCount ?? 0) }} 次</p>

        <section class="api-usage-chart">
          <h2>近 30 天用量</h2>
          <div>
            <i v-for="day in summary?.dailyUsage ?? []" :key="day.date" :title="`${day.date}: ${formatNumber(day.inputTokens + day.outputTokens)} tokens`" :style="{ height: `${Math.max(3, ((day.inputTokens + day.outputTokens) / maxDailyTokens) * 100)}%` }"></i>
          </div>
        </section>

        <section class="api-models">
          <h2>可调用模型</h2>
          <div class="api-model-grid">
            <article v-for="model in summary?.models ?? []" :key="model.key">
              <strong>{{ model.displayName }}</strong>
              <code>{{ model.key }}</code>
              <small>{{ model.category }} · {{ model.contextWindow ? `${Math.round(model.contextWindow / 1024)}K` : '上下文未配置' }}</small>
            </article>
          </div>
          <p v-if="summary && !summary.models.length" class="api-empty-hint">
            暂无可调用模型，请联系管理员开通。
          </p>
        </section>
      </section>

      <section v-else-if="activeSection === 'keys'" class="api-section">
        <header><h1>API Keys</h1><p>密钥只在创建或重置时显示一次，请妥善保存。</p></header>
        <div v-if="newSecret" class="api-secret-once">
          <div><strong>新的 API Key</strong><p>{{ newSecret }}</p></div>
          <button type="button" @click="copyText('new-secret', newSecret)"><Copy v-if="copied !== 'new-secret'" :size="16" /><Check v-else :size="16" />复制</button>
        </div>
        <form class="api-key-create" @submit.prevent="createKey">
          <input v-model="keyName" maxlength="128" placeholder="Key 名称，例如：项目 A" />
          <button type="submit"><KeyRound :size="16" />生成 API Key</button>
        </form>
        <div class="api-key-list">
          <article v-for="key in keys" :key="key.id">
            <div><strong>{{ key.name }}</strong><code>{{ key.maskedKey }}</code><small>{{ key.enabled ? '启用' : '已禁用' }} · 创建于 {{ new Date(key.createdAt).toLocaleString() }}</small></div>
            <button type="button" @click="resetKey(key)"><RefreshCw :size="15" />重置</button>
          </article>
          <p v-if="!keys.length">还没有 API Key。</p>
        </div>
      </section>

      <ApiActivityPanel v-else-if="activeSection === 'requests' || activeSection === 'logs'" :key="activeSection" mode="user" :initial-tab="activeSection" @update:tab="activeSection = $event" />
      <section v-else class="api-section api-docs">
        <header><h1>接口文档</h1><p>使用统一 OpenAI 兼容路径。请在请求头中携带自己的 API Key。</p></header>
        <article>
          <h2>Base URL</h2>
          <div class="api-code-line"><code>{{ summary?.baseUrl }}</code><button type="button" @click="copyText('base', summary?.baseUrl ?? '')"><Copy :size="15" /></button></div>
        </article>
        <article>
          <h2>当前可调用模型</h2>
          <div v-if="summary?.models.length" class="api-model-grid">
            <article v-for="model in summary.models" :key="model.key">
              <strong>{{ model.displayName }}</strong>
              <code>{{ model.key }}</code>
              <small>{{ model.category }} · {{ model.contextWindow ? `${Math.round(model.contextWindow / 1024)}K` : '上下文未配置' }}</small>
            </article>
          </div>
          <p v-else class="api-empty-hint">暂无可调用模型，请联系管理员开通。</p>
        </article>
        <article>
          <h2>认证方式</h2>
          <p>所有接口都需要 HTTP Header：</p>
          <pre><code>Authorization: Bearer $SITP_API_KEY</code></pre>
          <p>API Key 只在创建或重置时显示一次。若 Key 泄露，请在本页面重置，旧 Key 会立即失效。</p>
        </article>
        <article>
          <h2>模型列表</h2>
          <pre><code>curl {{ summary?.baseUrl }}/models \
  -H "Authorization: Bearer $SITP_API_KEY"</code></pre>
          <p>返回当前账号可调用的模型列表，可用于选择请求中的 model 参数。</p>
        </article>
        <article>
          <h2>对话生成</h2>
          <table class="api-param-table">
            <thead><tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td>model</td><td>string</td><td>是</td><td>模型列表返回的模型 ID</td></tr>
              <tr><td>messages</td><td>array</td><td>是</td><td>OpenAI 兼容消息数组，支持 system/user/assistant</td></tr>
              <tr><td>stream</td><td>boolean</td><td>否</td><td>true 时返回 SSE 流式响应</td></tr>
            </tbody>
          </table>
          <pre><code>curl {{ summary?.baseUrl }}/chat/completions \
  -H "Authorization: Bearer $SITP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "{{ chatModels[0]?.key || 'model-key' }}",
    "messages": [{"role": "user", "content": "你好，请介绍一下你自己"}],
    "stream": true
  }'</code></pre>
          <h3>Python</h3>
          <pre><code>from openai import OpenAI

client = OpenAI(
    api_key="你的 API Key",
    base_url="{{ summary?.baseUrl }}"
)

response = client.chat.completions.create(
    model="{{ chatModels[0]?.key || 'model-key' }}",
    messages=[{"role": "user", "content": "你好，请介绍一下你自己"}],
    stream=False,
)

print(response.choices[0].message.content)</code></pre>
          <h3>Java</h3>
          <pre><code>HttpClient client = HttpClient.newHttpClient();
String body = """
{
  "model": "{{ chatModels[0]?.key || 'model-key' }}",
  "messages": [{"role": "user", "content": "你好，请介绍一下你自己"}],
  "stream": false
}
""";

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("{{ summary?.baseUrl }}/chat/completions"))
    .header("Authorization", "Bearer " + System.getenv("SITP_API_KEY"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    .build();

HttpResponse&lt;String&gt; response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.body());</code></pre>
          <h3>Node.js</h3>
          <pre><code>const response = await fetch("{{ summary?.baseUrl }}/chat/completions", {
  method: "POST",
  headers: {
    Authorization: `Bearer ${process.env.SITP_API_KEY}`,
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    model: "{{ chatModels[0]?.key || 'model-key' }}",
    messages: [{ role: "user", content: "你好，请介绍一下你自己" }],
    stream: false
  })
});

console.log(await response.json());</code></pre>
        </article>
        <article>
          <h2>Embedding</h2>
          <pre><code>curl {{ summary?.baseUrl }}/embeddings \
  -H "Authorization: Bearer $SITP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"{{ embeddingModels[0]?.key || 'embedding-model' }}","input":"需要向量化的文本"}'</code></pre>
          <p>用于文本向量化。消耗的 Token 会计入 API 独立额度。</p>
        </article>
        <article>
          <h2>Rerank</h2>
          <pre><code>curl {{ summary?.baseUrl }}/rerank \
  -H "Authorization: Bearer $SITP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"{{ rerankModels[0]?.key || 'rerank-model' }}","query":"问题","documents":["候选文档1","候选文档2"]}'</code></pre>
          <p>用于对候选文档按相关性重新排序，适合检索增强场景。</p>
        </article>
        <article>
          <h2>语音识别</h2>
          <pre><code>curl {{ summary?.baseUrl }}/audio/transcriptions \
  -H "Authorization: Bearer $SITP_API_KEY" \
  -F "model={{ speechModels[0]?.key || 'speech-model' }}" \
  -F "file=@recording.wav"</code></pre>
          <p>上传音频文件并返回识别文本。请使用模型列表中的语音识别模型。</p>
        </article>
        <article>
          <h2>错误格式与常见错误</h2>
          <pre><code>{
  "error": {
    "message": "API token quota exceeded. Please contact your administrator.",
    "type": "quota_exceeded",
    "code": "quota_exceeded"
  }
}</code></pre>
          <table class="api-param-table">
            <thead><tr><th>HTTP 状态码</th><th>错误码</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td>401</td><td>invalid_api_key</td><td>未提供 Key、Key 错误或已被禁用</td></tr>
              <tr><td>403</td><td>model_not_allowed</td><td>当前账号不可调用该模型</td></tr>
              <tr><td>429</td><td>quota_exceeded</td><td>API Token 额度不足，请联系管理员</td></tr>
              <tr><td>502</td><td>upstream_error</td><td>模型服务调用失败或超时</td></tr>
            </tbody>
          </table>
        </article>
      </section>
    </section>
  </main>
</template>
