<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const channels = ref([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)

const form = reactive({
  name: '',
  baseUrl: '',
  credential: '',
  enabled: true,
})

const enabledCount = computed(() => channels.value.filter((channel) => channel.enabled).length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    channels.value = await api.listChannels()
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', baseUrl: '', credential: '', enabled: true })
  showForm.value = true
}

function openEdit(channel) {
  editingId.value = channel.id
  Object.assign(form, {
    name: channel.name,
    baseUrl: channel.baseUrl,
    credential: '',
    enabled: channel.enabled,
  })
  showForm.value = true
}

async function save() {
  if (!form.name.trim() || !form.baseUrl.trim()) {
    notify('请填写渠道名称和基础地址', 'error')
    return
  }
  if (!editingId.value && !form.credential.trim()) {
    notify('新建渠道必须提供上游凭据', 'error')
    return
  }

  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      credential: form.credential.trim() || null,
      authType: 'bearer',
      enabled: form.enabled,
    }
    if (editingId.value) {
      await api.updateChannel(editingId.value, payload)
      notify('渠道已更新')
    } else {
      await api.createChannel(payload)
      notify('渠道已创建')
    }
    showForm.value = false
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(channel) {
  if (!window.confirm(`确定删除渠道「${channel.name}」？`)) return
  try {
    await api.deleteChannel(channel.id)
    notify('渠道已删除')
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
        <h2>上游接入点</h2>
        <p>一个渠道对应一个 OpenAI Chat Completions 兼容地址。</p>
      </div>
      <button type="button" class="button primary" @click="openCreate">
        <Icon name="plus" :size="17" />
        新建渠道
      </button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>渠道总数</span>
        <strong>{{ channels.length }}</strong>
        <small>所有已配置接入点</small>
      </div>
      <div class="stat-card">
        <span>启用中</span>
        <strong class="text-success">{{ enabledCount }}</strong>
        <small>可参与别名路由</small>
      </div>
      <div class="stat-card accent">
        <span>凭据策略</span>
        <strong>AES-256-GCM</strong>
        <small>接口不回显上游凭据</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <div v-if="loading" class="loading-state">
        <span class="spinner"></span>
        正在读取渠道…
      </div>
      <div v-else-if="channels.length === 0" class="empty-state">
        <div class="empty-icon"><Icon name="channels" :size="26" /></div>
        <h3>还没有渠道</h3>
        <p>先接入一个 OpenAI 兼容上游，再创建模型别名。</p>
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建渠道
        </button>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>基础地址</th>
              <th>状态</th>
              <th>凭据</th>
              <th>更新时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="channel in channels" :key="channel.id">
              <td>
                <div class="primary-cell">{{ channel.name }}</div>
                <div class="mono muted small">{{ channel.id }}</div>
              </td>
              <td><span class="mono address">{{ channel.baseUrl }}</span></td>
              <td>
                <span class="badge" :class="channel.enabled ? 'success' : 'neutral'">
                  {{ channel.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td>
                <span class="badge" :class="channel.credentialSet ? 'info' : 'warning'">
                  {{ channel.credentialSet ? '已配置' : '未配置' }}
                </span>
              </td>
              <td class="muted">{{ formatTime(channel.updatedAt) }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="icon-button" title="编辑" @click="openEdit(channel)">
                    <Icon name="edit" :size="16" />
                  </button>
                  <button type="button" class="icon-button danger" title="删除" @click="remove(channel)">
                    <Icon name="trash" :size="16" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>渠道名称</span>
          <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
          <small>用于在别名候选中识别这个接入点。</small>
        </label>
        <label class="field">
          <span>基础地址</span>
          <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" autocomplete="off" />
          <small>网关会在此地址后追加 <code>/chat/completions</code>。</small>
        </label>
        <label class="field">
          <span>上游凭据</span>
          <input
            v-model="form.credential"
            type="password"
            :placeholder="editingId ? '留空保持当前凭据' : '输入上游 Bearer Token'"
            autocomplete="new-password"
          />
          <small>v1 只支持 Bearer。凭据会加密存储，保存后不再回显。</small>
        </label>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>启用渠道</strong>
            <small>停用后不会参与新的别名路由。</small>
          </span>
        </label>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存渠道' }}
        </button>
      </template>
    </Modal>
  </section>
</template>
