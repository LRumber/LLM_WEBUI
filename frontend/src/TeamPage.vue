<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Library, Plus, Trash2, UserPlus, Users } from 'lucide-vue-next'

interface Team {
  id: string
  name: string
  description?: string | null
  memberRole: 'owner' | 'admin' | 'member'
  memberCount: number
}

interface TeamMember {
  userId: number
  accountNumber: string
  userNumber?: string | null
  displayName: string
  role: 'owner' | 'admin' | 'member'
}

const teams = ref<Team[]>([])
const selectedId = ref<string | null>(null)
const members = ref<TeamMember[]>([])
const createOpen = ref(false)
const createForm = ref({ name: '', description: '' })
const memberIdentifier = ref('')
const memberRole = ref<'admin' | 'member'>('member')
const errorMessage = ref('')
const administratorStatus = ref<{ administrator: boolean; latest: { status: string; reviewNote: string | null } | null } | null>(null)
const applicationOpen = ref(false)
const applicationReason = ref('')
const applicationError = ref('')
const applicationSaving = ref(false)

const loadAdministratorStatus = async () => {
  const response = await fetch('/api/teams/administrator-request')
  if (!response.ok) throw new Error('申请状态加载失败，请刷新重试')
  administratorStatus.value = await response.json()
}

const submitApplication = async () => {
  if (applicationSaving.value) return
  applicationSaving.value = true
  applicationError.value = ''
  try {
    const response = await fetch('/api/teams/administrator-request', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: applicationReason.value }),
    })
    if (!response.ok) {
      const detail = await response.json().catch(() => null)
      throw new Error(detail?.message || '申请提交失败，请重试')
    }
    applicationOpen.value = false
    applicationReason.value = ''
    await loadAdministratorStatus()
  } catch (error) {
    applicationError.value = error instanceof Error ? error.message : '申请提交失败，请重试'
  } finally {
    applicationSaving.value = false
  }
}

const selected = computed(() => teams.value.find((team) => team.id === selectedId.value) ?? null)
const canManage = computed(() => selected.value?.memberRole === 'owner' || selected.value?.memberRole === 'admin')

const roleLabel = (role: Team['memberRole']) => {
  if (role === 'owner') return '所有者'
  if (role === 'admin') return '管理员'
  return '成员'
}

const friendlyTeamCreateError = (message?: string) => {
  if (message === 'Only a designated team administrator can create a team') {
    return '当前不是团队管理员，请联系系统管理员进行添加'
  }
  return message || '当前不是团队管理员，请联系系统管理员进行添加'
}

const friendlyTeamMemberError = (message?: string) => {
  if (!message) return '添加成员失败，请确认用户已登录过本系统'
  if (message.includes('User has not signed in') || message.includes('shadow user')) {
    return '未找到该用户，请确认用户已登录过本系统，并输入姓名、工号、账号或用户 ID'
  }
  if (message.includes('Team management permission')) return '当前账号没有团队成员管理权限'
  return message
}

const loadTeams = async () => {
  const response = await fetch('/api/teams')
  if (!response.ok) return
  teams.value = await response.json()
  if (!selectedId.value && teams.value.length) selectedId.value = teams.value[0].id
}

const loadMembers = async () => {
  if (!selectedId.value) {
    members.value = []
    return
  }
  const response = await fetch(`/api/teams/${selectedId.value}/members`)
  members.value = response.ok ? await response.json() : []
}

const selectTeam = async (teamId: string) => {
  selectedId.value = teamId
  errorMessage.value = ''
  await loadMembers()
}

const createTeam = async () => {
  errorMessage.value = ''
  const response = await fetch('/api/teams', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(createForm.value),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    errorMessage.value = friendlyTeamCreateError(error?.message)
    return
  }
  const created: Team = await response.json()
  createOpen.value = false
  createForm.value = { name: '', description: '' }
  await loadTeams()
  await selectTeam(created.id)
}

const addMember = async () => {
  if (!selectedId.value || !memberIdentifier.value.trim()) return
  errorMessage.value = ''
  const response = await fetch(`/api/teams/${selectedId.value}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifier: memberIdentifier.value, role: memberRole.value }),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    errorMessage.value = friendlyTeamMemberError(error?.message)
    return
  }
  memberIdentifier.value = ''
  await Promise.all([loadMembers(), loadTeams()])
}

const removeMember = async (member: TeamMember) => {
  if (!selectedId.value || member.role === 'owner') return
  if (!window.confirm(`确定移除成员“${member.displayName}”吗？`)) return
  const response = await fetch(`/api/teams/${selectedId.value}/members/${member.userId}`, { method: 'DELETE' })
  if (response.ok) await Promise.all([loadMembers(), loadTeams()])
}

const createTeamKnowledgeBase = async () => {
  if (!selectedId.value || !canManage.value) return
  const name = window.prompt('请输入团队知识库名称')?.trim()
  if (!name) return
  const response = await fetch(`/api/teams/${selectedId.value}/knowledge-bases`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description: `${selected.value?.name || ''}团队共享资料`, icon: 'team' }),
  })
  if (!response.ok) return window.alert('团队知识库创建失败')
  window.location.href = '/app/knowledge'
}

onMounted(async () => {
  void loadAdministratorStatus().catch((error) => { applicationError.value = error.message })
  await loadTeams()
  await loadMembers()
})
</script>

<template>
  <main class="team-page">
    <aside class="team-sidebar">
      <a href="/app"><ArrowLeft :size="17" />返回对话</a>
      <div class="team-brand"><Users :size="21" /><strong>团队空间</strong></div>
      <button class="team-create-button" type="button" @click="createOpen = true"><Plus :size="16" />创建团队</button>
      <div class="team-administrator-application">
        <span v-if="administratorStatus?.administrator">已是团队管理员</span>
        <span v-else-if="administratorStatus?.latest?.status === 'pending'">团队管理员申请待审批</span>
        <template v-else-if="administratorStatus">
          <button type="button" @click="applicationError = ''; applicationOpen = true">申请成为团队管理员</button>
          <small v-if="administratorStatus.latest?.status === 'rejected'">上次申请未通过：{{ administratorStatus.latest.reviewNote || '未填写审批意见' }}</small>
        </template>
        <small v-if="applicationError && !applicationOpen" role="alert">{{ applicationError }}</small>
      </div>
      <button
        v-for="team in teams"
        :key="team.id"
        class="team-item"
        :class="{ active: selectedId === team.id }"
        type="button"
        @click="selectTeam(team.id)"
      >
        <span>{{ team.name.slice(0, 1) }}</span>
        <div><strong>{{ team.name }}</strong><small>{{ team.memberCount }} 位成员</small></div>
      </button>
    </aside>

    <section class="team-content">
      <template v-if="selected">
        <header>
          <div><p>团队空间</p><h1>{{ selected.name }}</h1><span>{{ selected.description || '暂未填写团队说明' }}</span></div>
          <div class="team-header-actions">
            <button v-if="canManage" type="button" @click="createTeamKnowledgeBase"><Library :size="14" />创建团队知识库</button>
            <b>{{ roleLabel(selected.memberRole) }}</b>
          </div>
        </header>

        <section v-if="canManage" class="team-add-member">
          <div><UserPlus :size="18" /><span><strong>添加已有用户</strong><small>输入姓名、工号、账号或平台用户 ID</small></span></div>
          <form @submit.prevent="addMember">
            <input v-model="memberIdentifier" required placeholder="姓名 / 工号 / 账号 / ID" />
            <select v-model="memberRole"><option value="member">普通成员</option><option value="admin">团队管理员</option></select>
            <button type="submit">添加</button>
          </form>
          <p v-if="errorMessage">{{ errorMessage }}</p>
        </section>

        <div class="team-member-heading"><strong>团队成员</strong><span>{{ members.length }} 人</span></div>
        <div class="team-member-list">
          <article v-for="member in members" :key="member.userId">
            <span class="team-member-avatar">{{ member.displayName.slice(0, 1) }}</span>
            <div><strong>{{ member.displayName }}</strong><small><template v-if="member.userNumber">工号 {{ member.userNumber }} · </template>ID {{ member.userId }}</small></div>
            <b>{{ roleLabel(member.role) }}</b>
            <button v-if="canManage && member.role !== 'owner'" type="button" title="移除成员" @click="removeMember(member)"><Trash2 :size="15" /></button>
          </article>
        </div>
      </template>

      <div v-else class="team-empty">
        <Users :size="38" />
        <h1>还没有加入团队</h1>
        <p>成为团队管理员后，可以创建第一个团队；你也可以在左侧提交申请。</p>
        <button type="button" @click="createOpen = true"><Plus :size="16" />创建团队</button>
        <span v-if="errorMessage">{{ errorMessage }}</span>
      </div>
    </section>

    <div v-if="applicationOpen" class="team-dialog-backdrop" @click.self="!applicationSaving && (applicationOpen = false)">
      <form class="team-dialog" @submit.prevent="submitApplication">
        <header><div><h2>申请成为团队管理员</h2><p>审批通过后可创建团队。</p></div><button type="button" :disabled="applicationSaving" @click="applicationOpen = false">×</button></header>
        <label>申请理由<textarea v-model="applicationReason" required maxlength="1000" rows="4" :disabled="applicationSaving" placeholder="请说明创建团队的用途"></textarea></label>
        <p v-if="applicationError" role="alert">{{ applicationError }}</p>
        <footer><button type="button" :disabled="applicationSaving" @click="applicationOpen = false">取消</button><button class="primary" type="submit" :disabled="applicationSaving || !applicationReason.trim()">{{ applicationSaving ? '提交中…' : '提交申请' }}</button></footer>
      </form>
    </div>

    <div v-if="createOpen" class="team-dialog-backdrop" @click.self="createOpen = false">
      <form class="team-dialog" @submit.prevent="createTeam">
        <header><div><h2>创建团队</h2><p>创建后你将成为团队所有者。</p></div><button type="button" @click="createOpen = false">×</button></header>
        <label>团队名称<input v-model="createForm.name" maxlength="100" required /></label>
        <label>团队说明<textarea v-model="createForm.description" maxlength="500" rows="4"></textarea></label>
        <p v-if="errorMessage">{{ errorMessage }}</p>
        <footer><button type="button" @click="createOpen = false">取消</button><button class="primary" type="submit">创建</button></footer>
      </form>
    </div>
  </main>
</template>
