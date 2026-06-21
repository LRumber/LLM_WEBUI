<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElProgress, ElTag } from 'element-plus'
import { ArrowLeft, BookOpen, FileText, Library, Pencil, Plus, RefreshCw, Trash2, Upload } from 'lucide-vue-next'

interface KnowledgeBase {
  id: string
  name: string
  description?: string | null
  icon?: string | null
  spaceType: 'personal' | 'team' | 'project'
  memberRole: 'owner' | 'admin' | 'editor' | 'viewer'
  documentCount: number
}

interface KnowledgeDocument {
  id: string
  fileId: string
  originalName: string
  mediaType: string
  sizeBytes: number
  fileStatus: 'uploaded' | 'processing' | 'ready' | 'failed'
  status: string
  chunkCount: number
  errorMessage?: string | null
}

interface UploadedAsset { id: string }

interface KnowledgeSearchResult {
  chunkId: string
  filename: string
  content: string
  pageNumber?: number | null
  titlePath?: string | null
  keywordScore: number
  vectorScore: number
  score: number
}

/** Personal knowledge-space management stays separate from the chat workspace. */
const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedId = ref<string | null>(null)
const documents = ref<KnowledgeDocument[]>([])
const loading = ref(false)
const uploading = ref(false)
const editorOpen = ref(false)
const editingId = ref<string | null>(null)
const form = ref({ name: '', description: '', icon: 'book' })
const fileInput = ref<HTMLInputElement | null>(null)
const searchQuery = ref('')
const searchResults = ref<KnowledgeSearchResult[]>([])
const searching = ref(false)
let pollTimer: number | null = null

const selected = computed(() => knowledgeBases.value.find((item) => item.id === selectedId.value) ?? null)
const processingDocuments = computed(() => documents.value.some(
  (document) => document.fileStatus === 'uploaded' || document.fileStatus === 'processing',
))

const statusLabel = (document: KnowledgeDocument) => {
  if (document.fileStatus === 'failed') return '解析失败'
  if (document.fileStatus === 'uploaded') return '等待解析'
  if (document.fileStatus === 'processing') return '正在解析'
  if (document.status === 'ready') return '可用'
  return '等待向量化'
}

const statusType = (document: KnowledgeDocument) => {
  if (document.fileStatus === 'failed') return 'danger'
  if (document.status === 'ready') return 'success'
  return 'warning'
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const loadKnowledgeBases = async () => {
  const response = await fetch('/api/knowledge-bases')
  if (!response.ok) throw new Error('无法加载知识库')
  knowledgeBases.value = await response.json()
  if (!selectedId.value && knowledgeBases.value.length) selectedId.value = knowledgeBases.value[0].id
  if (selectedId.value && !knowledgeBases.value.some((item) => item.id === selectedId.value)) {
    selectedId.value = knowledgeBases.value[0]?.id ?? null
  }
}

const loadDocuments = async () => {
  if (!selectedId.value) {
    documents.value = []
    return
  }
  const response = await fetch(`/api/knowledge-bases/${selectedId.value}/documents`)
  if (!response.ok) throw new Error('无法加载知识库文档')
  documents.value = await response.json()
}

const selectKnowledgeBase = async (id: string) => {
  selectedId.value = id
  await loadDocuments()
}

const openEditor = (knowledgeBase?: KnowledgeBase) => {
  editingId.value = knowledgeBase?.id ?? null
  form.value = {
    name: knowledgeBase?.name ?? '',
    description: knowledgeBase?.description ?? '',
    icon: knowledgeBase?.icon ?? 'book',
  }
  editorOpen.value = true
}

const saveKnowledgeBase = async () => {
  if (!form.value.name.trim()) return
  const url = editingId.value ? `/api/knowledge-bases/${editingId.value}` : '/api/knowledge-bases'
  const response = await fetch(url, {
    method: editingId.value ? 'PATCH' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form.value),
  })
  if (!response.ok) return window.alert('知识库保存失败')
  const created = editingId.value ? null : await response.json() as KnowledgeBase
  editorOpen.value = false
  await loadKnowledgeBases()
  if (created) await selectKnowledgeBase(created.id)
}

const deleteKnowledgeBase = async (knowledgeBase: KnowledgeBase) => {
  if (!window.confirm(`确定删除知识库“${knowledgeBase.name}”吗？原始文件不会被删除。`)) return
  const response = await fetch(`/api/knowledge-bases/${knowledgeBase.id}`, { method: 'DELETE' })
  if (!response.ok) return window.alert('知识库删除失败')
  if (selectedId.value === knowledgeBase.id) selectedId.value = null
  await loadKnowledgeBases()
  await loadDocuments()
}

/** Uploads to private object storage first, then attaches only the returned owned asset id. */
const uploadDocuments = async (event: Event) => {
  const input = event.target as HTMLInputElement
  if (!selectedId.value || !input.files?.length) return
  uploading.value = true
  try {
    for (const file of Array.from(input.files)) {
      const body = new FormData()
      body.append('file', file)
      const uploadResponse = await fetch('/api/files', { method: 'POST', body })
      if (!uploadResponse.ok) throw new Error(`${file.name} 上传失败`)
      const asset = await uploadResponse.json() as UploadedAsset
      const attachResponse = await fetch(`/api/knowledge-bases/${selectedId.value}/documents`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileId: asset.id }),
      })
      if (!attachResponse.ok) throw new Error(`${file.name} 无法加入知识库`)
    }
    await Promise.all([loadDocuments(), loadKnowledgeBases()])
  } catch (error) {
    window.alert((error as Error).message)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

const retryDocument = async (document: KnowledgeDocument) => {
  const response = await fetch(`/api/files/${document.fileId}/retry`, { method: 'POST' })
  if (!response.ok) return window.alert('重新解析失败')
  await loadDocuments()
}

const removeDocument = async (document: KnowledgeDocument) => {
  if (!selectedId.value || !window.confirm(`从知识库移除“${document.originalName}”吗？`)) return
  const response = await fetch(`/api/knowledge-bases/${selectedId.value}/documents/${document.id}`, { method: 'DELETE' })
  if (!response.ok) return window.alert('移除文档失败')
  await Promise.all([loadDocuments(), loadKnowledgeBases()])
}

/** Runs the same permission-filtered retrieval endpoint that chat will consume later. */
const testRetrieval = async () => {
  if (!selectedId.value || !searchQuery.value.trim()) return
  searching.value = true
  try {
    const response = await fetch(`/api/knowledge-bases/${selectedId.value}/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: searchQuery.value, topK: 8 }),
    })
    if (!response.ok) throw new Error('检索失败，请检查文档状态和 Embedding 配置')
    searchResults.value = await response.json()
  } catch (error) {
    window.alert((error as Error).message)
  } finally {
    searching.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await loadKnowledgeBases()
    await loadDocuments()
  } catch (error) {
    window.alert((error as Error).message)
  } finally {
    loading.value = false
  }
  // Polling is local and bounded; no external service is required in the final offline environment.
  pollTimer = window.setInterval(() => {
    if (processingDocuments.value) loadDocuments()
  }, 3000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
})
</script>

<template>
  <main class="knowledge-page">
    <aside class="knowledge-sidebar">
      <a class="knowledge-back" href="/app"><ArrowLeft :size="17" />返回对话</a>
      <div class="knowledge-brand"><Library :size="21" /><strong>知识库</strong></div>
      <button class="knowledge-create" type="button" @click="openEditor()"><Plus :size="16" />新建知识库</button>
      <div class="knowledge-space-label">个人知识库</div>
      <button v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" class="knowledge-space-item" :class="{ active: selectedId === knowledgeBase.id }" type="button" @click="selectKnowledgeBase(knowledgeBase.id)">
        <span><BookOpen :size="16" /></span>
        <div><strong>{{ knowledgeBase.name }}</strong><small>{{ knowledgeBase.documentCount }} 个文档</small></div>
      </button>
      <p v-if="!knowledgeBases.length && !loading" class="knowledge-sidebar-empty">还没有知识库</p>
    </aside>

    <section class="knowledge-content">
      <template v-if="selected">
        <header class="knowledge-header">
          <div><p>个人知识库</p><h1>{{ selected.name }}</h1><span>{{ selected.description || '暂未填写说明' }}</span></div>
          <div class="knowledge-header-actions">
            <button type="button" @click="openEditor(selected)"><Pencil :size="15" />编辑</button>
            <button class="danger" type="button" @click="deleteKnowledgeBase(selected)"><Trash2 :size="15" />删除</button>
          </div>
        </header>

        <div class="knowledge-toolbar">
          <div><strong>文档</strong><span>{{ documents.length }} 个文件</span></div>
          <input ref="fileInput" type="file" multiple accept=".pdf,.docx,.xlsx,.pptx,.txt,.md" @change="uploadDocuments" />
          <button type="button" :disabled="uploading" @click="fileInput?.click()"><Upload :size="16" />{{ uploading ? '上传中…' : '上传文档' }}</button>
        </div>

        <section class="knowledge-search-test">
          <header><div><strong>检索测试</strong><span>查看实际召回的原文和相关度</span></div></header>
          <form @submit.prevent="testRetrieval">
            <input v-model="searchQuery" placeholder="输入一个问题或关键词" />
            <button type="submit" :disabled="searching || !searchQuery.trim()">{{ searching ? '检索中…' : '开始检索' }}</button>
          </form>
          <div v-if="searchResults.length" class="knowledge-search-results">
            <article v-for="(result, index) in searchResults" :key="result.chunkId">
              <div><strong>{{ index + 1 }}. {{ result.filename }}</strong><span>相关度 {{ Math.round(result.score * 100) }}%<template v-if="result.pageNumber"> · 第 {{ result.pageNumber }} 页</template></span></div>
              <p>{{ result.content }}</p>
              <small>关键词 {{ result.keywordScore.toFixed(2) }} · 向量 {{ result.vectorScore.toFixed(2) }}</small>
            </article>
          </div>
          <p v-else-if="searchQuery && !searching" class="knowledge-search-empty">尚无检索结果</p>
        </section>

        <div v-if="documents.length" class="knowledge-document-list">
          <article v-for="document in documents" :key="document.id" class="knowledge-document-card">
            <span class="knowledge-file-icon"><FileText :size="20" /></span>
            <div class="knowledge-file-main">
              <strong>{{ document.originalName }}</strong>
              <small>{{ formatSize(document.sizeBytes) }} · {{ document.mediaType }}</small>
              <ElProgress v-if="document.fileStatus === 'uploaded' || document.fileStatus === 'processing'" :percentage="100" :show-text="false" :stroke-width="2" :indeterminate="true" :duration="1.5" />
              <p v-if="document.errorMessage">{{ document.errorMessage }}</p>
            </div>
            <ElTag :type="statusType(document)" effect="light" round>{{ statusLabel(document) }}</ElTag>
            <button v-if="document.fileStatus === 'failed'" type="button" title="重新解析" @click="retryDocument(document)"><RefreshCw :size="15" /></button>
            <button type="button" title="移除文档" @click="removeDocument(document)"><Trash2 :size="15" /></button>
          </article>
        </div>
        <div v-else class="knowledge-empty-documents">
          <FileText :size="30" /><h2>上传第一份文档</h2>
          <p>支持 PDF、Word、Excel、PPT、TXT 和 Markdown，文件将存入私有对象存储。</p>
          <button type="button" @click="fileInput?.click()"><Upload :size="16" />选择文件</button>
        </div>
      </template>

      <div v-else class="knowledge-empty-page">
        <Library :size="36" /><h1>创建你的第一个知识库</h1>
        <p>集中管理资料，后续可在对话中检索并引用原文。</p>
        <button type="button" @click="openEditor()"><Plus :size="16" />新建知识库</button>
      </div>
    </section>

    <div v-if="editorOpen" class="knowledge-dialog-backdrop" @click.self="editorOpen = false">
      <form class="knowledge-dialog" @submit.prevent="saveKnowledgeBase">
        <header><div><h2>{{ editingId ? '编辑知识库' : '新建知识库' }}</h2><p>当前创建个人空间，只有你可以访问。</p></div><button type="button" @click="editorOpen = false">×</button></header>
        <label>名称<input v-model="form.name" maxlength="100" required placeholder="例如：产品资料" /></label>
        <label>说明<textarea v-model="form.description" maxlength="500" rows="4" placeholder="说明知识库包含哪些内容"></textarea></label>
        <footer><button type="button" @click="editorOpen = false">取消</button><button class="primary" type="submit">保存</button></footer>
      </form>
    </div>
  </main>
</template>
