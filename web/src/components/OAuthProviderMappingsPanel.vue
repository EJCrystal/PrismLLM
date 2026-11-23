<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { ApiError } from '../types/api'
import type {
  Channel,
  ModelSuggestionsResponse,
  OAuthModelMappingMode,
  OAuthProviderModelMapping,
  OAuthProviderModelMappingsResponse,
} from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import {
  buildOAuthMappingChanges,
  filterOAuthModels,
  oauthMappingDraft,
  oauthMappingDraftEquals,
  oauthProviderLabel,
  type OAuthMappingDraft,
} from '../utils/oauthAccount'
import Icon from './Icon.vue'
import OAuthMappingInput from './OAuthMappingInput.vue'

const props = defineProps<{
  provider: string
  providerLabel?: string
  channels?: Channel[]
}>()

const emit = defineEmits<{
  (event: 'saved'): void
}>()

const baseline = ref<OAuthProviderModelMappingsResponse | null>(null)
const suggestions = ref<ModelSuggestionsResponse | null>(null)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const candidateError = ref('')
const notice = ref('')
const query = ref('')
const showInactive = ref(false)
const conflictConfirmed = ref(false)

const drafts = reactive<Record<string, OAuthMappingDraft>>({})
const dirtyModels = reactive(new Set<string>())
const conflictingModels = reactive(new Set<string>())
const requests = createLatestRequestGuard()
let loadGeneration = 0

const baselineByModel = computed(() =>
  new Map((baseline.value?.mappings ?? []).map((item) => [item.upstreamModel, item])),
)
const busy = computed(() => loading.value || saving.value)
const hasConflict = computed(() => conflictingModels.size > 0)
const dirtyCount = computed(() => dirtyModels.size)

const displayTitle = computed(() => props.providerLabel || oauthProviderLabel(props.provider))

const allModels = computed(() => {
  const rows = baseline.value?.mappings ?? []
  const filtered = showInactive.value ? rows : rows.filter((row) => row.catalogActive || dirtyModels.has(row.upstreamModel))
  return [...filtered].sort((a, b) => a.upstreamModel.localeCompare(b.upstreamModel))
})

const inactiveCount = computed(() =>
  (baseline.value?.mappings ?? []).filter((row) => !row.catalogActive).length,
)

const filteredRows = computed(() =>
  allModels.value.filter((row) =>
    filterOAuthModels([row.upstreamModel, mappingPublicId(row.upstreamModel)], query.value).length > 0,
  ),
)

const mappingCandidates = computed(() => {
  const candidates = new Map<string, Set<string>>()
  const add = (value: string | null | undefined, hint: string) => {
    if (!value?.trim()) return
    const id = value.trim()
    if (!candidates.has(id)) candidates.set(id, new Set())
    candidates.get(id)!.add(hint)
  }
  for (const mapping of baseline.value?.mappings ?? []) {
    if (mapping.catalogActive) add(mapping.publicModelId, '当前提供商有效对外 ID')
  }
  for (const channel of props.channels ?? []) {
    const mapped = new Map(channel.modelMappings.map((item) => [item.upstreamModel, item]))
    for (const model of channel.models) {
      add(mapped.get(model)?.canonicalModelId ?? model, `已有对外 ID · ${channel.name}`)
    }
  }
  for (const item of suggestions.value?.data ?? []) {
    for (const candidate of item.candidates) {
      add(candidate.canonicalId, `目录建议 · ${candidate.provider} · ${candidate.developer}`)
    }
  }
  for (const [model, draft] of Object.entries(drafts)) {
    add(draft.canonicalModelId, `当前值 / 草稿 · ${model}`)
  }
  return [...candidates]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([value, hints]) => ({
      value,
      label: value,
      hint: [...hints].join('；'),
      searchText: `${value} ${[...hints].join(' ')}`,
    }))
})

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function mappingDraftFor(model: string): OAuthMappingDraft {
  return drafts[model] ?? oauthMappingDraft(baselineByModel.value.get(model))
}

function setMappingDraft(model: string, draft: OAuthMappingDraft) {
  drafts[model] = draft
  const base = oauthMappingDraft(baselineByModel.value.get(model))
  if (oauthMappingDraftEquals(draft, base)) {
    dirtyModels.delete(model)
    conflictingModels.delete(model)
  } else {
    dirtyModels.add(model)
  }
  conflictConfirmed.value = false
}

function changeMode(model: string, mode: OAuthModelMappingMode) {
  const base = baselineByModel.value.get(model)
  const current = mappingDraftFor(model)
  setMappingDraft(model, {
    mode,
    canonicalModelId: current.canonicalModelId || base?.automaticCanonicalModelId || model,
  })
}

function changeManual(model: string, canonicalModelId: string) {
  setMappingDraft(model, { mode: 'manual', canonicalModelId })
}

function discardDraft(model: string) {
  dirtyModels.delete(model)
  conflictingModels.delete(model)
  drafts[model] = oauthMappingDraft(baselineByModel.value.get(model))
  conflictConfirmed.value = false
}

function discardAllDrafts() {
  for (const model of [...dirtyModels]) discardDraft(model)
  notice.value = ''
}

function mappingPublicId(model: string): string {
  const base = baselineByModel.value.get(model)
  const draft = mappingDraftFor(model)
  if (!dirtyModels.has(model) && base) return base.publicModelId
  if (draft.mode === 'manual') return draft.canonicalModelId.trim() || '未填写'
  if (draft.mode === 'upstream') return model
  return base?.automaticCanonicalModelId ?? model
}

function mappingSource(model: string): string {
  const base = baselineByModel.value.get(model)
  if (dirtyModels.has(model)) return '待保存；当前来源：' + (base?.mappingSource ?? '无')
  return base?.mappingSource ?? '无'
}

function rowChanged(previous: OAuthProviderModelMapping, next: OAuthProviderModelMapping): boolean {
  return previous.mode !== next.mode
    || previous.canonicalModelId !== next.canonicalModelId
    || previous.publicModelId !== next.publicModelId
    || previous.mappingSource !== next.mappingSource
    || previous.catalogActive !== next.catalogActive
}

function resetState() {
  loadGeneration++
  requests.invalidate()
  baseline.value = null
  suggestions.value = null
  loadError.value = ''
  candidateError.value = ''
  notice.value = ''
  query.value = ''
  conflictConfirmed.value = false
  dirtyModels.clear()
  conflictingModels.clear()
  for (const key of Object.keys(drafts)) delete drafts[key]
}

async function load(): Promise<boolean> {
  const generation = ++loadGeneration
  loading.value = true
  loadError.value = ''
  try {
    const result = await requests.run(async () => {
      const mapping = await api.getOAuthProviderModelMappings(props.provider)
      const models = mapping.mappings.map((item) => item.upstreamModel)
      let suggestionResult: PromiseSettledResult<ModelSuggestionsResponse> | null = null
      if (models.length) {
        const [settled] = await Promise.allSettled([api.getModelSuggestions(models)])
        suggestionResult = settled
      }
      return { mapping, suggestionResult }
    })
    if (!result.isLatest || generation !== loadGeneration) return false
    const { mapping, suggestionResult } = result.value
    if (suggestionResult) {
      if (suggestionResult.status === 'fulfilled') {
        suggestions.value = suggestionResult.value
        candidateError.value = suggestionResult.value.error
          || (!suggestionResult.value.loaded ? '目录候选暂不可用，仍可手工输入对外 ID。' : '')
      } else {
        suggestions.value = null
        candidateError.value = '目录候选读取失败，仍可手工输入并保存对外 ID。'
      }
    } else {
      suggestions.value = null
      candidateError.value = ''
    }

    const previousRows = baselineByModel.value
    const nextRows = new Map(mapping.mappings.map((item) => [item.upstreamModel, item]))
    for (const model of [...dirtyModels]) {
      const prev = previousRows.get(model)
      const next = nextRows.get(model)
      if (!next) {
        dirtyModels.delete(model)
        conflictingModels.delete(model)
        continue
      }
      if (
        !prev
        || rowChanged(prev, next)
        || (mappingDraftFor(model).mode === 'auto' && prev.automaticCanonicalModelId !== next.automaticCanonicalModelId)
      ) {
        conflictingModels.add(model)
        conflictConfirmed.value = false
      }
    }
    baseline.value = mapping
    for (const row of mapping.mappings) {
      if (!dirtyModels.has(row.upstreamModel)) {
        drafts[row.upstreamModel] = oauthMappingDraft(row)
      }
    }
    return true
  } catch (cause) {
    if (generation === loadGeneration && (cause as { isLatest?: boolean }).isLatest !== false) {
      loadError.value = errorMessage(cause)
    }
    return false
  } finally {
    if (generation === loadGeneration && !requests.hasActive()) loading.value = false
  }
}

async function save() {
  if (busy.value || !baseline.value || dirtyCount.value === 0) return
  if (hasConflict.value && !conflictConfirmed.value) {
    notify('共享映射已变化，请先核对冲突并勾选确认后再保存。', 'error')
    return
  }
  let changes
  try {
    changes = buildOAuthMappingChanges(drafts, [...dirtyModels])
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    return
  }
  if (!changes.length) return
  saving.value = true
  notice.value = ''
  try {
    const updated = await api.updateOAuthProviderModelMappings(props.provider, {
      expectedRevision: baseline.value.revision,
      changes,
    })
    baseline.value = updated
    dirtyModels.clear()
    conflictingModels.clear()
    conflictConfirmed.value = false
    for (const row of updated.mappings) {
      drafts[row.upstreamModel] = oauthMappingDraft(row)
    }
    notify(`${displayTitle.value} 共享模型映射已保存`)
    emit('saved')
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) {
      for (const change of changes) conflictingModels.add(change.upstreamModel)
      conflictConfirmed.value = false
      notice.value = '共享模型映射发生版本冲突，本次修改未保存。草稿已保留，请核对最新值后确认保存。'
      await load()
      notify(notice.value, 'warning')
    } else {
      notify(errorMessage(cause), 'error')
    }
  } finally {
    saving.value = false
  }
}

watch(() => props.provider, () => {
  resetState()
  void load()
})

onMounted(() => { void load() })
onBeforeUnmount(() => {
  loadGeneration++
  requests.invalidate()
})

defineExpose({ reload: load })
</script>

<template>
  <div class="provider-mappings-panel">
    <div class="provider-mappings-intro">
      <p class="muted small">
        对外 ID 映射由 <strong>{{ displayTitle }}</strong> 下的全部订阅账号共享
        <template v-if="baseline"> · 受影响账号 {{ baseline.affectedAccountCount }} 个 · 版本 {{ baseline.revision }}</template>。
        此处只调整对外模型 ID 映射，不会改动任何账号的模型授权范围。
      </p>
      <div class="provider-mappings-head-actions">
        <button type="button" class="button ghost mini" :disabled="busy" @click="load">
          <Icon name="refresh" :size="14" />
          刷新
        </button>
      </div>
    </div>

    <div v-if="loadError" class="callout danger small" role="alert">
      共享映射读取失败：{{ loadError }}
      <button type="button" class="link-button" :disabled="busy" @click="load">重试</button>
    </div>
    <p v-if="candidateError" class="muted small" role="status">{{ candidateError }}</p>
    <div v-if="notice || hasConflict" class="callout warning small" role="alert">
      <div>{{ notice }}</div>
      <div v-if="hasConflict">共享映射冲突：{{ conflictingModels.size }} 个草稿行已变化，请核对最新共享值并确认后再保存。</div>
      <label v-if="hasConflict" class="checkbox-row">
        <input v-model="conflictConfirmed" type="checkbox" :disabled="busy || !!loadError" />
        <span>我已核对最新共享映射，确认覆盖到当前版本</span>
      </label>
    </div>

    <div class="oauth-model-picker">
      <div class="oauth-model-picker-toolbar">
        <label class="oauth-model-search">
          <Icon name="search" :size="15" />
          <input
            v-model="query"
            autocomplete="off"
            aria-label="搜索共享模型映射"
            placeholder="搜索上游模型或对外 ID"
          />
        </label>
        <label v-if="inactiveCount > 0" class="checkbox-row small">
          <input v-model="showInactive" type="checkbox" />
          <span>显示休眠模型（{{ inactiveCount }}）</span>
        </label>
        <span class="muted small">共 {{ allModels.length }} 项<template v-if="dirtyCount > 0"> · {{ dirtyCount }} 项待保存</template></span>
      </div>

      <div v-if="loading && !baseline" class="muted small provider-mappings-empty" role="status">正在读取共享模型映射…</div>
      <div v-else-if="allModels.length === 0" class="muted small provider-mappings-empty">
        当前提供商还没有已同步的上游模型目录。请先在「账号池」中添加账号并同步模型目录。
      </div>
      <div v-else class="oauth-model-list oauth-mapping-list provider-mappings-list">
        <div class="oauth-mapping-head small" aria-hidden="true">
          <span>上游模型</span><span>对外 ID / public ID</span><span>映射模式</span><span>映射来源</span>
        </div>
        <div v-for="row in filteredRows" :key="row.upstreamModel" class="oauth-mapping-row" :data-provider-mapping-model="row.upstreamModel">
          <div class="oauth-mapping-upstream">
            <span class="mono">{{ row.upstreamModel }}</span>
            <span v-if="!row.catalogActive" class="badge neutral small" title="当前所有账号目录均未包含该模型，保留历史人工映射">休眠</span>
          </div>
          <div class="oauth-mapping-public">
            <span class="mono small" :title="mappingPublicId(row.upstreamModel)">{{ mappingPublicId(row.upstreamModel) }}</span>
            <OAuthMappingInput
              v-if="mappingDraftFor(row.upstreamModel).mode === 'manual'"
              :model-value="mappingDraftFor(row.upstreamModel).canonicalModelId"
              :upstream-model="row.upstreamModel"
              :options="mappingCandidates"
              :disabled="busy"
              @update:model-value="changeManual(row.upstreamModel, $event)"
            />
            <span v-if="conflictingModels.has(row.upstreamModel)" class="small oauth-conflict-baseline">
              最新共享值：{{ row.publicModelId }}（{{ row.mode }}；来源 {{ row.mappingSource ?? '无' }}）
            </span>
          </div>
          <div class="oauth-mapping-mode">
            <select
              :value="mappingDraftFor(row.upstreamModel).mode"
              :aria-label="row.upstreamModel + ' 映射模式'"
              :disabled="busy"
              @change="changeMode(row.upstreamModel, ($event.target as HTMLSelectElement).value as OAuthModelMappingMode)"
            >
              <option value="auto">自动</option>
              <option value="manual">手工</option>
              <option value="upstream">使用上游 ID</option>
            </select>
            <button
              v-if="mappingDraftFor(row.upstreamModel).mode !== 'auto'"
              type="button"
              class="link-button small"
              :disabled="busy"
              @click="changeMode(row.upstreamModel, 'auto')"
            >恢复自动</button>
          </div>
          <div class="oauth-mapping-source muted small">
            {{ mappingSource(row.upstreamModel) }}
            <button
              v-if="dirtyModels.has(row.upstreamModel)"
              type="button"
              class="link-button small"
              :disabled="busy"
              @click="discardDraft(row.upstreamModel)"
            >撤销</button>
          </div>
        </div>
        <div v-if="filteredRows.length === 0" class="oauth-model-empty muted small">
          没有匹配「{{ query }}」的模型。
        </div>
      </div>
    </div>

    <div class="provider-mappings-footer">
      <button v-if="dirtyCount > 0" type="button" class="button ghost" :disabled="busy" @click="discardAllDrafts">
        放弃全部草稿
      </button>
      <button
        type="button"
        class="button primary"
        :disabled="busy || dirtyCount === 0 || !!loadError || (hasConflict && !conflictConfirmed)"
        @click="save"
      >
        {{ saving ? '保存中…' : dirtyCount > 0 ? `保存映射修改（${dirtyCount}）` : '保存映射修改' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.provider-mappings-panel {
  display: grid;
  gap: 12px;
}

.provider-mappings-intro {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.provider-mappings-intro p {
  margin: 0;
  flex: 1 1 320px;
}

.provider-mappings-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.provider-mappings-empty {
  padding: 20px 14px;
  text-align: center;
}

.provider-mappings-list {
  max-height: 460px;
}

.provider-mappings-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 4px;
}

.oauth-conflict-baseline {
  color: var(--danger);
}
</style>
