<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import SecretInput from './SecretInput.vue'
import { filterKeyModels } from '../utils/keyModels'
import { parseManualModelIds } from '../utils/channelForm'
import { channelKeyRateLimitSummary } from '../utils/channelKeyRateLimit'
import { formatTime } from '../utils/format'
import { keyStateBadge, keyStateTone } from '../utils/channelKey'
import type { ChannelKeyEditorDraft, KeyModelOption } from '../types/channelEditor'

const props = defineProps<{
  channelKey: ChannelKeyEditorDraft
  concurrent: number
}>()

const emit = defineEmits<{
  (event: 'remove'): void
  (event: 'edit-rate-limit'): void
  (event: 'load-models'): void
}>()

const modelSearch = ref('')
const modelsOpen = ref(props.channelKey.models.length === 0)

// 停用到期后还有探测租约时仍然接不到请求，恢复时间要按两个时间的较晚者展示，
// 与后端 isRoutableAt 的口径一致。
const recoveryTimeText = computed(() => formatTime(
  Math.max(props.channelKey.autoDisableUntil || 0, props.channelKey.probeUntil || 0),
))
const stateBadge = computed(() => keyStateBadge(props.channelKey.state))
const stateTone = computed(() => keyStateTone(props.channelKey.state))
const modelOptions = computed<KeyModelOption[]>(() => {
  const options = new Map<string, KeyModelOption>()
  ;(props.channelKey.availableModels || []).forEach((model) => options.set(model.id, model))
  ;[...(props.channelKey.manualModels || []), ...(props.channelKey.models || [])].forEach((modelId) => {
    if (!options.has(modelId)) options.set(modelId, { id: modelId })
  })
  return [...options.values()].sort((left, right) => left.id.localeCompare(right.id))
})

const visibleModels = computed(() => filterKeyModels(modelOptions.value, modelSearch.value))

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function toggleState(): void {
  props.channelKey.state = props.channelKey.state === 'ACTIVE' ? 'MANUAL_DISABLED' : 'ACTIVE'
}

function toggleModel(modelId: string): void {
  const selected = new Set(props.channelKey.models)
  if (selected.has(modelId)) selected.delete(modelId)
  else selected.add(modelId)
  props.channelKey.models = [...selected].sort()
}

function selectAllModels(): void {
  props.channelKey.models = [...new Set([...props.channelKey.models, ...visibleModels.value.map(model => model.id)])].sort()
}

function deselectVisibleModels(): void {
  const visible = new Set(visibleModels.value.map(model => model.id))
  props.channelKey.models = props.channelKey.models.filter(id => !visible.has(id))
}

function clearModels(): void {
  props.channelKey.models = []
}

function openManualModels(): void {
  props.channelKey.showManualModels = true
  nextTick(() => document.getElementById(`manual-models-${props.channelKey.localId}`)?.focus())
}

function closeManualModels(): void {
  props.channelKey.showManualModels = false
  props.channelKey.manualModelsText = ''
  props.channelKey.manualModelsError = ''
  nextTick(() => document.getElementById(`manual-models-button-${props.channelKey.localId}`)?.focus())
}

function addManualModels(): void {
  try {
    const models = parseManualModelIds(props.channelKey.manualModelsText)
    props.channelKey.manualModels = [...new Set([...props.channelKey.manualModels, ...models])]
    props.channelKey.models = [...new Set([...props.channelKey.models, ...models])].sort()
    // 用户确认使用这些手动模型后，不再阻止保存；之后重新读取失败会再次置位
    props.channelKey.modelsError = ''
    closeManualModels()
  } catch (cause) {
    props.channelKey.manualModelsError = errorMessage(cause)
  }
}
</script>

<template>
  <div class="channel-key-editor">
    <div class="channel-key-editor-head">
      <label class="field">
        <span>密钥名称</span>
        <input v-model="channelKey.name" placeholder="例如 primary" autocomplete="off" />
      </label>
      <label class="field">
        <span>优先级</span>
        <input
          v-model.number="channelKey.priority"
          type="number"
          min="1"
          max="1000"
          step="1"
          title="1..1000，数值越小越优先"
        />
        <small>1..1000，越小越优先；同级轮询。</small>
      </label>
      <div class="channel-key-editor-status">
        <span>状态</span>
        <div class="channel-key-status-row">
          <span class="channel-key-state-dot" :class="`is-${stateTone}`" aria-hidden="true"></span>
          <span class="badge" :class="stateBadge.class">{{ stateBadge.label }}</span>
          <button type="button" class="button subtle mini" @click="toggleState">
            {{ channelKey.state === 'ACTIVE' ? '停用' : '启用' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="channelKey.originalState === 'AUTO_PERMANENT'" class="callout" :class="channelKey.state === 'ACTIVE' ? 'info' : 'danger'">
      <strong>{{ channelKey.state === 'ACTIVE' ? '保存后重新启用' : '已自动永久停用' }}<template v-if="channelKey.autoDisableReason">：{{ channelKey.autoDisableReason }}</template></strong>
      <p>{{ channelKey.state === 'ACTIVE' ? '保存后将人工恢复为正常状态，可保留当前凭据或在下方替换凭据；失败计数会重置，但上游问题未解决时可能再次触发禁用。' : '上游鉴权、账号或余额问题触发了永久禁用，不会自动恢复。修复上游问题后可点击“启用”并保存，当前凭据无需更换。' }}</p>
    </div>
    <div v-else-if="channelKey.originalState === 'AUTO_TEMPORARY'" class="callout" :class="channelKey.state === 'ACTIVE' ? 'info' : 'warning'">
      <strong>{{ channelKey.state === 'ACTIVE' ? '保存后提前启用' : '已自动临时停用' }}<template v-if="channelKey.autoDisableReason">：{{ channelKey.autoDisableReason }}</template></strong>
      <p>
        <template v-if="channelKey.autoDisableUntil">恢复时间：{{ recoveryTimeText }}（到期后自动可用）。</template>
        {{ channelKey.state === 'ACTIVE' ? '提前启用无需更换凭据，保存后立即恢复为正常状态。' : '可等待到期自动恢复，或点击“启用”并在保存后提前恢复。' }}
      </p>
    </div>

    <label class="field">
      <span>上游凭据</span>
      <SecretInput
        v-model="channelKey.credential"
        :placeholder="channelKey.id ? '留空保持当前凭据' : '输入上游凭据（Bearer Token 或 Anthropic API key）'"
        autocomplete="new-password"
      />
      <small v-if="channelKey.id">已保存的凭据不会回显；留空表示保持不变。</small>
    </label>

    <div class="channel-key-rate-limit-row">
      <div>
        <strong>上游分组限流</strong>
        <small>{{ channelKeyRateLimitSummary(channelKey.rateLimit) }}</small>
        <small v-if="channelKey.id && concurrent > 0" class="channel-key-live is-active">
          在途 {{ concurrent }} 个请求
        </small>
      </div>
      <button type="button" class="button ghost mini" @click="emit('edit-rate-limit')">
        {{ channelKey.rateLimit?.enabled ? '编辑限流' : '配置限流' }}
      </button>
    </div>

    <div class="channel-key-models">
      <div class="channel-key-models-head">
        <div>
          <strong>模型授权</strong>
          <small>已授权 {{ channelKey.models.length }} 个；同一模型可以配置在多个密钥上。</small>
        </div>
        <div class="channel-key-models-actions">
          <span v-if="channelKey.modelsError" class="badge danger">读取失败</span>
          <button
            type="button"
            class="button ghost mini"
            :aria-expanded="modelsOpen"
            :aria-controls="`key-models-panel-${channelKey.localId}`"
            @click="modelsOpen = !modelsOpen"
          >
            {{ modelsOpen ? '收起模型' : '管理模型' }}
          </button>
        </div>
      </div>

      <div v-if="modelsOpen" :id="`key-models-panel-${channelKey.localId}`" class="channel-key-models-panel">
        <div class="channel-model-auth-toolbar">
          <button type="button" class="button subtle mini" :disabled="channelKey.modelsLoading" @click="emit('load-models')">
            {{ channelKey.modelsLoading ? '读取中…' : '读取模型' }}
          </button>
          <button
            :id="`manual-models-button-${channelKey.localId}`"
            type="button"
            class="button subtle mini"
            :aria-expanded="channelKey.showManualModels"
            :aria-controls="`manual-models-panel-${channelKey.localId}`"
            @click="openManualModels"
          >手动添加</button>
          <button type="button" class="button ghost mini" @click="selectAllModels">全选当前结果</button>
          <button type="button" class="button ghost mini" @click="deselectVisibleModels">取消当前结果</button>
          <button type="button" class="button ghost mini" @click="clearModels">清空全部选择</button>
        </div>

        <div v-if="channelKey.showManualModels" :id="`manual-models-panel-${channelKey.localId}`" class="channel-key-manual-models">
          <label class="field" :for="`manual-models-${channelKey.localId}`">
            <span>上游模型 ID</span>
            <textarea
              :id="`manual-models-${channelKey.localId}`"
              v-model="channelKey.manualModelsText"
              rows="3"
              placeholder="填写上游实际使用的模型 ID，可批量粘贴"
              spellcheck="false"
              autocapitalize="off"
              :aria-invalid="!!channelKey.manualModelsError"
              :aria-describedby="`manual-models-hint-${channelKey.localId}`"
              @input="channelKey.manualModelsError = ''"
            ></textarea>
            <small :id="`manual-models-hint-${channelKey.localId}`">支持换行、逗号、分号或空格分隔；添加后自动勾选。</small>
          </label>
          <div v-if="channelKey.manualModelsError" class="callout danger" role="alert">{{ channelKey.manualModelsError }}</div>
          <div class="channel-key-manual-model-actions">
            <button type="button" class="button subtle mini" :disabled="!channelKey.manualModelsText.trim()" @click="addManualModels">添加并选中</button>
            <button type="button" class="button ghost mini" @click="closeManualModels">取消</button>
          </div>
        </div>

        <div v-if="channelKey.modelsError" class="callout danger" role="alert">
          {{ channelKey.modelsError }}
          <p>可点击“手动添加”填写上游模型 ID，添加并选中后即可保存。</p>
        </div>

        <label class="field">
          <span>搜索模型（{{ visibleModels.length }}/{{ modelOptions.length }}）</span>
          <input v-model="modelSearch" type="search" placeholder="输入模型 ID 过滤…" autocomplete="off" />
        </label>
        <p v-if="modelOptions.length && !visibleModels.length" class="muted small" role="status">没有匹配的模型。</p>
        <div v-if="visibleModels.length" class="channel-model-auth-body">
          <div class="channel-model-auth-list">
            <label v-for="model in visibleModels" :key="model.id" class="channel-model-option">
              <input
                type="checkbox"
                :checked="channelKey.models.includes(model.id)"
                @change="toggleModel(model.id)"
              />
              <span>
                <code>{{ model.id }}</code>
                <small v-if="model.name">{{ model.name }}</small>
                <small v-if="model.ownedBy">上游 owned_by: {{ model.ownedBy }}</small>
              </span>
            </label>
          </div>
        </div>
        <div v-else-if="!modelOptions.length" class="channel-model-auth-placeholder">
          点击“读取模型”获取列表，或点击“手动添加”填写该密钥可用的上游模型 ID。
        </div>
      </div>
    </div>

    <div class="channel-key-editor-danger">
      <button type="button" class="button ghost mini channel-key-delete" @click="emit('remove')">删除密钥</button>
    </div>
  </div>
</template>

<style scoped>
.channel-key-editor {
  display: grid;
  gap: 14px;
  min-width: 0;
  --key-form-muted: color-mix(in srgb, var(--muted) 80%, var(--ink));
}

.channel-key-editor-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px 160px;
  align-items: start;
  gap: 12px;
}

.channel-key-editor-status {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.channel-key-editor-status > span {
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
}

.channel-key-status-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 40px;
  height: auto;
}

.channel-key-status-row .badge {
  height: auto;
  margin: 0;
  padding: 4px 8px;
}

.channel-key-state-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--line-strong);
}

.channel-key-state-dot.is-success { background: var(--success-fill); }
.channel-key-state-dot.is-warning { background: var(--warning-fill); }
.channel-key-state-dot.is-danger { background: var(--danger-fill); }
.channel-key-state-dot.is-neutral { background: var(--line-strong); }

.channel-key-rate-limit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  background: var(--glass-field);
}

.channel-key-rate-limit-row > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.channel-key-rate-limit-row strong { font-size: 13px; }
.channel-key-rate-limit-row small { color: var(--key-form-muted); font-size: 11px; line-height: 1.5; }
.channel-key-rate-limit-row small.channel-key-live.is-active { color: var(--success); font-weight: 600; }
.channel-key-rate-limit-row .button { flex: 0 0 auto; min-height: 36px; }

.channel-key-models {
  display: grid;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px dashed var(--glass-line);
}

.channel-key-models-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.channel-key-models-head > div:first-child {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.channel-key-models-head strong { font-size: 13px; }
.channel-key-models-head small { color: var(--key-form-muted); font-size: 11px; line-height: 1.5; }

.channel-key-models-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  flex: 0 0 auto;
}

.channel-key-models-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  background: var(--glass-field);
}

.channel-key-editor .channel-model-auth-toolbar {
  margin-top: 0;
}

.channel-key-editor .channel-model-auth-list {
  max-height: 280px;
  background: var(--glass-field-strong);
}

.channel-key-editor .channel-model-option:hover {
  background: var(--info-soft);
}

.channel-key-editor .channel-model-auth-placeholder {
  margin: 0;
  color: var(--key-form-muted);
  font-size: 11px;
}

.channel-key-editor .field small { color: var(--key-form-muted); }
.channel-key-editor .field input::placeholder { color: var(--key-form-muted); opacity: 1; }
.channel-key-editor .channel-model-option small { color: var(--key-form-muted); }

.channel-key-manual-models,
.channel-key-manual-model-actions {
  display: grid;
  gap: 10px;
}

.channel-key-manual-model-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.channel-key-editor-danger {
  display: flex;
  justify-content: flex-end;
  padding-top: 2px;
}

.channel-key-delete {
  min-height: 34px;
  color: var(--danger);
}

@media (max-width: 900px) {
  .channel-key-editor-head {
    grid-template-columns: minmax(0, 1fr) 120px;
  }

  .channel-key-editor-status {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .channel-key-editor .channel-model-auth-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .channel-key-editor {
    gap: 12px;
  }

  .channel-key-editor-head {
    grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
    gap: 12px;
  }

  .channel-key-editor-head > .field:first-child {
    grid-column: 1 / -1;
  }

  .channel-key-editor-head > .field:nth-child(2) {
    grid-column: 1;
  }

  .channel-key-editor-status {
    grid-column: 2;
  }

  .channel-key-status-row {
    min-height: 44px;
  }

  .channel-key-rate-limit-row {
    align-items: stretch;
    flex-direction: column;
  }

  .channel-key-rate-limit-row .button {
    align-self: flex-start;
    min-height: 40px;
  }

  .channel-key-rate-limit-row small,
  .channel-key-models-head small {
    font-size: 12px;
  }

  .channel-key-models-head {
    align-items: stretch;
    flex-direction: column;
  }

  .channel-key-models-actions {
    align-self: flex-start;
  }

  .channel-key-models-panel {
    padding: 10px;
  }

  .channel-key-editor .channel-model-auth-list {
    grid-template-columns: 1fr;
    max-height: 42vh;
  }

  .channel-key-editor-danger {
    justify-content: stretch;
  }

  .channel-key-delete {
    width: 100%;
    min-height: 40px;
  }
}
/* 降低透明度时，编辑器内部的玻璃卡片回退到实色。 */
@media (prefers-reduced-transparency: reduce) {
  .channel-key-editor,
  .channel-key-models-panel,
  .channel-key-rate-limit-row {
    background: var(--panel);
    border-color: var(--line);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
