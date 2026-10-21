<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const aliases = ref([])
const channels = ref([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)

const form = reactive({
  alias: '',
  enabled: true,
  candidates: [],
})

const maxAttempts = 2

const enabledCount = computed(() => aliases.value.filter((item) => item.enabled).length)
const candidateCount = computed(() =>
  aliases.value.reduce((total, item) => total + item.candidates.length, 0),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [aliasList, channelList] = await Promise.all([api.listAliases(), api.listChannels()])
    aliases.value = aliasList
    channels.value = channelList
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function channelName(channelId) {
  return channels.value.find((channel) => channel.id === channelId)?.name || channelId.slice(0, 8)
}

function openCreate() {
  if (channels.value.length === 0) {
    notify('请先创建至少一个渠道', 'error')
    return
  }
  editingId.value = null
  form.alias = ''
  form.enabled = true
  form.candidates = [{ channelId: channels.value[0].id, upstreamModel: '' }]
  showForm.value = true
}

function openEdit(alias) {
  editingId.value = alias.id
  form.alias = alias.alias
  form.enabled = alias.enabled
  form.candidates = alias.candidates.map((candidate) => ({
    channelId: candidate.channelId,
    upstreamModel: candidate.upstreamModel,
  }))
  showForm.value = true
}

function addCandidate() {
  form.candidates.push({
    channelId: channels.value[0]?.id || '',
    upstreamModel: '',
  })
}

function removeCandidate(index) {
  if (form.candidates.length === 1) return
  form.candidates.splice(index, 1)
}

function moveCandidate(index, offset) {
  const target = index + offset
  if (target < 0 || target >= form.candidates.length) return
  const [candidate] = form.candidates.splice(index, 1)
  form.candidates.splice(target, 0, candidate)
}

async function save() {
  if (!form.alias.trim()) {
    notify('请填写模型别名', 'error')
    return
  }
  if (form.candidates.length === 0 || form.candidates.some((item) => !item.channelId || !item.upstreamModel.trim())) {
    notify('每个候选都需要选择渠道并填写上游模型名', 'error')
    return
  }

  saving.value = true
  try {
    const payload = {
      alias: form.alias.trim(),
      enabled: form.enabled,
      candidates: form.candidates.map((item) => ({
        channelId: item.channelId,
        upstreamModel: item.upstreamModel.trim(),
      })),
    }
    if (editingId.value) {
      await api.updateAlias(editingId.value, payload)
      notify('模型别名已更新')
    } else {
      await api.createAlias(payload)
      notify('模型别名已创建')
    }
    showForm.value = false
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(alias) {
  if (!window.confirm(`确定删除模型别名「${alias.alias}」？`)) return
  try {
    await api.deleteAlias(alias.id)
    notify('模型别名已删除')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

function formatTime(value) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(value)
    : '—'
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>路由表</h2>
        <p>候选按顺序尝试；只有首 token 之前的失败才会切换到下一个。</p>
      </div>
      <button type="button" class="button primary" @click="openCreate">
        <Icon name="plus" :size="17" />
        新建别名
      </button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>别名总数</span>
        <strong>{{ aliases.length }}</strong>
        <small>调用方可见的模型名</small>
      </div>
      <div class="stat-card">
        <span>启用中</span>
        <strong class="text-success">{{ enabledCount }}</strong>
        <small>出现在 /v1/models</small>
      </div>
      <div class="stat-card accent">
        <span>候选总数</span>
        <strong>{{ candidateCount }}</strong>
        <small>跨渠道的有序路由目标</small>
      </div>
    </div>

    <div v-if="channels.length === 0 && !loading" class="callout warning">
      还没有可用渠道。先到「渠道」页面创建一个上游接入点，再配置别名。
    </div>
    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <div v-if="loading" class="loading-state">
        <span class="spinner"></span>
        正在读取路由表…
      </div>
      <div v-else-if="aliases.length === 0" class="empty-state">
        <div class="empty-icon"><Icon name="aliases" :size="26" /></div>
        <h3>还没有模型别名</h3>
        <p>别名是调用方请求里的 model 字段，可以映射到多个上游模型。</p>
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建别名
        </button>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>别名</th>
              <th>候选顺序</th>
              <th>状态</th>
              <th>更新时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alias in aliases" :key="alias.id">
              <td>
                <div class="primary-cell mono">{{ alias.alias }}</div>
                <div class="mono muted small">{{ alias.id }}</div>
              </td>
              <td>
                <div class="candidate-list">
                  <span v-for="(candidate, index) in alias.candidates" :key="candidate.id" class="candidate-chip">
                    <b>{{ index + 1 }}</b>
                    <span>{{ candidate.channelName }} / <code>{{ candidate.upstreamModel }}</code></span>
                  </span>
                </div>
              </td>
              <td>
                <span class="badge" :class="alias.enabled ? 'success' : 'neutral'">
                  {{ alias.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="muted">{{ formatTime(alias.updatedAt) }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="icon-button" title="编辑" @click="openEdit(alias)">
                    <Icon name="edit" :size="16" />
                  </button>
                  <button type="button" class="icon-button danger" title="删除" @click="remove(alias)">
                    <Icon name="trash" :size="16" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showForm" :title="editingId ? '编辑模型别名' : '新建模型别名'" wide @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <div class="form-row">
          <label class="field grow">
            <span>模型别名</span>
            <input v-model="form.alias" placeholder="例如 fast-chat" autocomplete="off" />
            <small>调用方在请求体里使用的 model。允许字母、数字、点、下划线和短横线。</small>
          </label>
          <label class="switch-row compact">
            <input v-model="form.enabled" type="checkbox" />
            <span class="switch"></span>
            <span>
              <strong>启用别名</strong>
              <small>停用后不出现在 /v1/models。</small>
            </span>
          </label>
        </div>

        <div class="candidate-editor">
          <div class="candidate-editor-head">
            <div>
              <span>候选顺序</span>
              <small>从上到下尝试，最多尝试 {{ maxAttempts }} 个候选（由服务端 MAX_ATTEMPTS 决定）。</small>
            </div>
            <button type="button" class="button subtle" @click="addCandidate">
              <Icon name="plus" :size="16" />
              添加候选
            </button>
          </div>

          <div v-for="(candidate, index) in form.candidates" :key="index" class="candidate-row">
            <span class="candidate-index">{{ index + 1 }}</span>
            <label class="field">
              <span>渠道</span>
              <select v-model="candidate.channelId">
                <option v-for="channel in channels" :key="channel.id" :value="channel.id">
                  {{ channel.name }}{{ channel.enabled ? '' : '（已停用）' }}
                </option>
              </select>
            </label>
            <label class="field grow">
              <span>上游模型名</span>
              <input v-model="candidate.upstreamModel" placeholder="例如 gpt-4o-mini" autocomplete="off" />
            </label>
            <div class="candidate-actions">
              <button
                type="button"
                class="icon-button"
                title="上移"
                :disabled="index === 0"
                @click="moveCandidate(index, -1)"
              >
                <Icon name="up" :size="16" />
              </button>
              <button
                type="button"
                class="icon-button"
                title="下移"
                :disabled="index === form.candidates.length - 1"
                @click="moveCandidate(index, 1)"
              >
                <Icon name="down" :size="16" />
              </button>
              <button
                type="button"
                class="icon-button danger"
                title="移除"
                :disabled="form.candidates.length === 1"
                @click="removeCandidate(index)"
              >
                <Icon name="trash" :size="16" />
              </button>
            </div>
          </div>
        </div>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存别名' }}
        </button>
      </template>
    </Modal>
  </section>
</template>
