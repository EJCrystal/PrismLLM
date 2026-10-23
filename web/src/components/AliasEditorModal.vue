<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({
  channels: { type: Array, required: true },
  alias: { type: Object, default: null },
  initialChannelId: { type: String, default: '' },
  initialModel: { type: String, default: '' },
})
const emit = defineEmits(['close', 'saved'])
const maxAttempts = 2
const saving = ref(false)
const channelModels = reactive({})
const modelLoading = reactive({})
const modelErrors = reactive({})

const initialChannelId = props.initialChannelId || props.channels[0]?.id || ''
const form = reactive({
  alias: props.alias?.alias || '',
  enabled: props.alias?.enabled ?? true,
  candidates: props.alias?.candidates?.length
    ? props.alias.candidates.map((candidate) => ({
        channelId: candidate.channelId,
        upstreamModel: candidate.upstreamModel,
      }))
    : [{ channelId: initialChannelId, upstreamModel: props.initialModel || '' }],
})

async function loadChannelModels(channelId, force = false) {
  if (!channelId) return
  const channel = props.channels.find((item) => item.id === channelId)
  if (channel && (channel.models || []).length === 0) {
    channelModels[channelId] = []
    modelErrors[channelId] = ''
    return
  }
  if (!force && hasUpstreamModelCache(channelId)) {
    channelModels[channelId] = getAuthorizedUpstreamModelCache(channelId)
    modelErrors[channelId] = ''
    return
  }
  if (modelLoading[channelId]) return
  modelLoading[channelId] = true
  modelErrors[channelId] = ''
  try {
    const response = await api.listChannelModels(channelId)
    const models = (response?.data || []).filter((model) => model.authorized)
    setUpstreamModelCache(channelId, models)
    channelModels[channelId] = models
  } catch (cause) {
    channelModels[channelId] = getAuthorizedUpstreamModelCache(channelId)
    modelErrors[channelId] = cause.message
  } finally {
    modelLoading[channelId] = false
  }
}

function modelsFor(channelId) {
  return channelModels[channelId] || []
}

function hasCachedModels(channelId) {
  return hasUpstreamModelCache(channelId)
}

function onChannelChange(candidate) {
  candidate.upstreamModel = ''
  loadChannelModels(candidate.channelId)
}

function addCandidate() {
  const channelId = props.channels[0]?.id || ''
  form.candidates.push({ channelId, upstreamModel: '' })
  loadChannelModels(channelId)
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
    if (props.alias?.id) {
      await api.updateAlias(props.alias.id, payload)
      notify('模型别名已更新')
    } else {
      await api.createAlias(payload)
      notify('模型别名已创建')
    }
    emit('saved')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  form.candidates.forEach((candidate) => loadChannelModels(candidate.channelId))
})
</script>

<template>
  <Modal :title="alias?.id ? '编辑模型别名' : '新建模型别名'" wide @close="$emit('close')">
    <form class="form-grid" @submit.prevent="save">
      <div class="form-row">
        <label class="field grow">
          <span>模型别名</span>
          <input v-model="form.alias" placeholder="例如 fast-chat" autocomplete="off" />
          <small>调用方在请求体里使用的 model。建议使用 models.dev 的 canonical ID（通常为小写）；允许字母、数字、点、下划线和短横线。</small>
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
            <select v-model="candidate.channelId" @change="onChannelChange(candidate)">
              <option v-for="channel in channels" :key="channel.id" :value="channel.id">
                {{ channel.name }}{{ channel.enabled ? '' : '（已停用）' }}
              </option>
            </select>
          </label>
          <label class="field grow">
            <span>上游模型名</span>
            <div class="model-input-row">
              <input
                v-model="candidate.upstreamModel"
                :list="`alias-models-${index}`"
                placeholder="例如 gpt-4o-mini"
                autocomplete="off"
              />
              <button
                type="button"
                class="button subtle mini"
                :disabled="modelLoading[candidate.channelId]"
                @click="loadChannelModels(candidate.channelId, true)"
              >
                {{ modelLoading[candidate.channelId] ? '读取中…' : (hasCachedModels(candidate.channelId) ? '刷新模型' : '获取模型') }}
              </button>
            </div>
            <datalist :id="`alias-models-${index}`">
              <option v-for="model in modelsFor(candidate.channelId)" :key="model.id" :value="model.id">
                {{ model.ownedBy || '' }}
              </option>
            </datalist>
            <small v-if="modelErrors[candidate.channelId]" class="text-danger">
              {{ modelErrors[candidate.channelId] }}{{ hasCachedModels(candidate.channelId) ? '（继续使用缓存）' : '' }}
            </small>
          </label>
          <div class="candidate-actions">
            <button type="button" class="icon-button" title="上移" :disabled="index === 0" @click="moveCandidate(index, -1)">
              <Icon name="up" :size="16" />
            </button>
            <button type="button" class="icon-button" title="下移" :disabled="index === form.candidates.length - 1" @click="moveCandidate(index, 1)">
              <Icon name="down" :size="16" />
            </button>
            <button type="button" class="icon-button danger" title="移除" :disabled="form.candidates.length === 1" @click="removeCandidate(index)">
              <Icon name="trash" :size="16" />
            </button>
          </div>
        </div>
      </div>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="saving || channels.length === 0" @click="save">
        {{ saving ? '保存中…' : '保存别名' }}
      </button>
    </template>
  </Modal>
</template>
