<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
const filterMode = ref<'all' | 'custom'>('all')
const showInactive = ref(false)
const showAddForm = ref(false)
const addInput = ref<HTMLInputElement | null>(null)
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

/** 新增草稿：服务端尚没有对应共享行，保存时按人工映射创建。 */
const newDraftModels = computed(() =>
  [...dirtyModels].filter((model) => !baselineByModel.value.has(model)),
)
/** 新增草稿没有可继承的目录默认值，因此不允许使用「自动」模式。 */
const uncreatableDrafts = computed(() =>
  newDraftModels.value.filter((model) => mappingDraftFor(model).mode === 'auto'),
)
/** 可随本次保存提交的草稿：已有共享行，或新增且选择了手工/上游模式。 */
const savableDraftModels = computed(() =>
  [...dirtyModels].filter((model) => baselineByModel.value.has(model) || mappingDraftFor(model).mode !== 'auto'),
)
const savableDraftCount = computed(() => savableDraftModels.value.length)

function newDraftRow(model: string) {
  return {
    upstreamModel: model,
    canonicalModelId: null,
    publicModelId: model,
    automaticCanonicalModelId: null,
    mode: 'auto' as const,
    mappingSource: null,
    catalogActive: false,
    enabled: false,
  }
}

const allModels = computed(() => {
  const rows = baseline.value?.mappings ?? []
  // 默认只显示启用账号已授权的模型；未启用的手工映射保留，待保存草稿不能被隐藏。
  const filtered = showInactive.value
    ? rows
    : rows.filter((row) => row.enabled || dirtyModels.has(row.upstreamModel))
  return [...filtered, ...newDraftModels.value.map(newDraftRow)]
    .sort((a, b) => a.upstreamModel.localeCompare(b.upstreamModel))
})

const inactiveCount = computed(() =>
  (baseline.value?.mappings ?? []).filter((row) => !row.enabled).length,
)

function isCustomRow(model: string): boolean {
  const base = baselineByModel.value.get(model)
  return mappingDraftFor(model).mode !== 'auto'
    || base?.mappingSource != null
    || !base
    || dirtyModels.has(model)
}

const customCount = computed(() =>
  allModels.value.filter((row) => isCustomRow(row.upstreamModel)).length,
)

const filteredRows = computed(() =>
  allModels.value.filter((row) => {
    if (filterMode.value === 'custom' && !isCustomRow(row.upstreamModel)) return false
    return filterOAuthModels([row.upstreamModel, mappingPublicId(row.upstreamModel)], query.value).length > 0
  }),
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
    if (mapping.enabled) add(mapping.publicModelId, '当前提供商已启用对外 ID')
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
    const id = draft.canonicalModelId?.trim()
    if (!id) continue
    const existing = candidates.get(id)
    if (!existing || existing.size === 0) {
      add(id, `当前草稿 · ${model}`)
    }
  }
  return [...candidates]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([value, hints]) => {
      const list = [...hints]
      const displayHints = list.slice(0, 2)
      const extra = list.length - displayHints.length
      return {
        value,
        label: value,
        hint: extra > 0 ? `${displayHints.join('；')} 等 ${list.length} 处` : displayHints.join('；'),
        searchText: `${value} ${list.join(' ')}`,
      }
    })
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
  const base = baselineByModel.value.get(model)
  if (base) {
    drafts[model] = oauthMappingDraft(base)
  } else {
    // 新增草稿没有基线行，撤销后直接移除，避免阻挡同名模型再次添加。
    delete drafts[model]
  }
  conflictConfirmed.value = false
}

function discardAllDrafts() {
  for (const model of [...dirtyModels]) discardDraft(model)
  notice.value = ''
}

const newModel = ref('')
const newModelMode = ref<'manual' | 'upstream'>('manual')

function openAddForm(prefill = '') {
  if (prefill) newModel.value = prefill
  showAddForm.value = true
  void nextTick(() => addInput.value?.focus())
}

function closeAddForm() {
  showAddForm.value = false
}

/** 新增一行手工映射草稿；保存时由后端创建共享行（先休眠，目录出现后自动激活）。 */
function addDraft() {
  if (busy.value) return
  const model = newModel.value.trim()
  if (!model) return
  if ([...model].length > 200) {
    notify('上游模型名不能超过 200 个字符', 'error')
    return
  }
  if (baselineByModel.value.has(model) || dirtyModels.has(model)) {
    notify(`共享映射中已存在模型「${model}」`, 'warning')
    return
  }
  drafts[model] = { mode: newModelMode.value, canonicalModelId: model }
  dirtyModels.add(model)
  conflictingModels.delete(model)
  conflictConfirmed.value = false
  newModel.value = ''
  showAddForm.value = false
  if (query.value.trim() && filterOAuthModels([model], query.value).length === 0) {
    query.value = ''
  }
}

function addFromQuery() {
  const model = query.value.trim()
  if (!model || busy.value) return
  newModelMode.value = 'manual'
  newModel.value = model
  addDraft()
}

function mappingPublicId(model: string): string {
  const base = baselineByModel.value.get(model)
  const draft = mappingDraftFor(model)
  if (!dirtyModels.has(model) && base) return base.publicModelId
  if (draft.mode === 'manual') return draft.canonicalModelId.trim() || '未填写'
  if (draft.mode === 'upstream') return model
  return base?.automaticCanonicalModelId ?? model
}

function rawSourceLabel(source: string | null | undefined): string {
  if (source === 'manual') return '手工'
  if (source === 'models_dev') return '历史目录'
  if (!source) return '默认'
  return source
}

function sourceMeta(model: string): {
  label: string
  tone: 'muted' | 'normal' | 'accent' | 'dirty' | 'warning'
  title?: string
} {
  const base = baselineByModel.value.get(model)
  const draft = mappingDraftFor(model)
  if (!base) {
    if (draft.mode === 'auto') {
      return {
        label: '需选模式',
        tone: 'warning',
        title: '新增映射无法使用自动模式，请选择手工或使用上游 ID',
      }
    }
    return {
      label: '新增草稿',
      tone: 'dirty',
      title: '新增草稿（保存后写入共享目录）',
    }
  }
  if (dirtyModels.has(model)) {
    return {
      label: '待保存',
      tone: 'dirty',
      title: `待保存；原来源：${base.mappingSource ?? '默认'}（${rawSourceLabel(base.mappingSource)}）`,
    }
  }
  if (base.mappingSource === 'manual') {
    return { label: '手工', tone: 'accent', title: 'manual' }
  }
  if (base.mappingSource === 'models_dev') {
    return { label: '历史目录', tone: 'normal', title: 'models_dev' }
  }
  if (base.mappingSource) {
    return { label: base.mappingSource, tone: 'normal', title: base.mappingSource }
  }
  return { label: '默认', tone: 'muted' }
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
  filterMode.value = 'all'
  showInactive.value = false
  showAddForm.value = false
  newModel.value = ''
  newModelMode.value = 'manual'
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
        // 服务端已没有该共享行：保留草稿并显示为不可提交，等待用户显式丢弃或模型恢复。
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
    // 清掉已不在共享目录且没有待保存修改的残留草稿，避免阻挡同名模型重新添加。
    for (const model of Object.keys(drafts)) {
      if (!nextRows.has(model) && !dirtyModels.has(model)) delete drafts[model]
    }
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
  if (busy.value || !baseline.value || savableDraftCount.value === 0) return
  if (hasConflict.value && !conflictConfirmed.value) {
    notify('共享映射已变化，请先核对冲突并勾选确认后再保存。', 'error')
    return
  }
  let changes
  try {
    changes = buildOAuthMappingChanges(drafts, savableDraftModels.value)
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
    // 保存未启用项后继续展示结果；重新打开面板仍默认只显示已启用项。
    if (updated.mappings.some((row) => !row.enabled && changes.some((change) => change.upstreamModel === row.upstreamModel))) {
      showInactive.value = true
    }
    // 新增但选了「自动」的草稿无法创建，保存成功后仍需保留。
    const remainingUncreatable = [...uncreatableDrafts.value]
    dirtyModels.clear()
    conflictingModels.clear()
    conflictConfirmed.value = false
    for (const model of remainingUncreatable) dirtyModels.add(model)
    for (const row of updated.mappings) {
      drafts[row.upstreamModel] = oauthMappingDraft(row)
    }
    if (remainingUncreatable.length) {
      notice.value = `已保存其余映射；${remainingUncreatable.length} 个新增草稿使用「自动」模式，未提交，已保留。请选择「手工」或「使用上游 ID」后再保存。`
      notify(notice.value, 'warning')
    } else {
      notify(`${displayTitle.value} 共享模型映射已保存`)
    }
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

defineExpose({
  reload: load,
  /** 供父级在切换/关闭前判断是否有未保存草稿。 */
  hasDirty: () => dirtyCount.value > 0,
})
</script>

<template>
  <div class="provider-mappings-panel">
    <div class="provider-mappings-intro">
      <div class="provider-mappings-context">
        <span class="provider-mappings-desc muted small">仅调整对外模型 ID；默认显示启用账号已授权的模型</span>
        <div v-if="baseline" class="provider-mappings-pills">
          <span class="provider-meta-pill">共享 {{ baseline.affectedAccountCount }} 个账号</span>
          <span class="provider-meta-pill mono">v{{ baseline.revision }}</span>
        </div>
      </div>
      <div class="provider-mappings-head-actions">
        <button type="button" class="button ghost mini" :disabled="busy" @click="load">
          <Icon name="refresh" :size="14" />
          刷新
        </button>
      </div>
    </div>

    <div
      v-if="loadError || candidateError || uncreatableDrafts.length || notice || hasConflict"
      class="provider-mappings-notices"
    >
      <div v-if="loadError" class="callout danger small" role="alert">
        共享映射读取失败：{{ loadError }}
        <button type="button" class="link-button" :disabled="busy" @click="load">重试</button>
      </div>
      <p v-if="candidateError" class="provider-candidate-note muted small" role="status">{{ candidateError }}</p>
      <p v-if="uncreatableDrafts.length" class="callout warning small" role="alert">
        {{ uncreatableDrafts.length }} 个新增草稿使用了「自动」模式，无法提交；请为它们选择「手工」或「使用上游 ID」。
      </p>
      <div v-if="notice || hasConflict" class="callout warning small" role="alert">
        <div v-if="notice">{{ notice }}</div>
        <div v-if="hasConflict">共享映射冲突：{{ conflictingModels.size }} 个草稿行已变化，请核对最新共享值并确认后再保存。</div>
        <label v-if="hasConflict" class="checkbox-row provider-conflict-confirm">
          <input v-model="conflictConfirmed" type="checkbox" :disabled="busy || !!loadError" />
          <span>我已核对最新共享映射，确认覆盖到当前版本</span>
        </label>
      </div>
    </div>

    <div class="provider-mappings-toolbar">
      <label class="provider-mapping-search">
        <Icon name="search" :size="14" />
        <input
          v-model="query"
          autocomplete="off"
          aria-label="搜索共享模型映射"
          placeholder="搜索上游模型或对外 ID"
        />
        <button
          v-if="query"
          type="button"
          class="provider-search-clear"
          aria-label="清空搜索"
          @click="query = ''"
        >
          <Icon name="close" :size="13" />
        </button>
      </label>
      <div class="provider-filter-group" role="group" aria-label="映射状态筛选">
        <button
          type="button"
          class="provider-filter-chip"
          :class="{ 'is-active': filterMode === 'all' }"
          :aria-pressed="filterMode === 'all'"
          @click="filterMode = 'all'"
        >
          {{ showInactive ? '全部' : '已启用' }} {{ allModels.length }}
        </button>
        <button
          type="button"
          class="provider-filter-chip"
          :class="{ 'is-active': filterMode === 'custom' }"
          :aria-pressed="filterMode === 'custom'"
          @click="filterMode = 'custom'"
        >
          自定义 {{ customCount }}
        </button>
        <button
          v-if="inactiveCount > 0"
          type="button"
          class="provider-filter-chip is-dormant"
          :class="{ 'is-active': showInactive }"
          :aria-pressed="showInactive"
          @click="showInactive = !showInactive"
        >
          含未启用 {{ inactiveCount }}
        </button>
      </div>
      <button
        type="button"
        class="button ghost mini provider-add-toggle"
        :aria-expanded="showAddForm"
        :disabled="busy"
        @click="showAddForm ? closeAddForm() : openAddForm()"
      >
        <Icon :name="showAddForm ? 'close' : 'plus'" :size="14" />
        <span>{{ showAddForm ? '收起新增' : '新增映射' }}</span>
      </button>
    </div>

    <div v-if="showAddForm" class="provider-mapping-add-bar">
      <label class="provider-mapping-add-input">
        <Icon name="plus" :size="14" />
        <input
          ref="addInput"
          v-model="newModel"
          autocomplete="off"
          aria-label="新增上游模型映射"
          placeholder="输入上游模型 ID，如 claude-sonnet-4-6"
          :disabled="busy"
          @keydown.enter.prevent="addDraft"
          @keydown.esc.prevent="closeAddForm"
        />
      </label>
      <select v-model="newModelMode" class="provider-mapping-add-mode" aria-label="新增映射模式" :disabled="busy">
        <option value="manual">手工指定对外 ID</option>
        <option value="upstream">使用上游 ID</option>
      </select>
      <div class="provider-mapping-add-actions">
        <button type="button" class="button subtle mini" :disabled="busy || !newModel.trim()" @click="addDraft">
          添加
        </button>
        <button type="button" class="button ghost mini" :disabled="busy" @click="closeAddForm">
          取消
        </button>
      </div>
    </div>

    <div v-if="loading && !baseline" class="provider-mappings-empty muted small" role="status">
      正在读取共享模型映射…
    </div>
    <div v-else-if="allModels.length === 0" class="provider-mappings-empty">
      <p class="muted small">
        {{ baseline?.mappings.length ? '当前没有已启用模型。可在「账号池」中启用账号并授权模型，或选择「含未启用」查看保留的映射。' : '当前提供商还没有共享模型。可直接手工新增映射，或在「账号池」中添加账号并同步模型目录。' }}
      </p>
      <button v-if="!showAddForm" type="button" class="button ghost mini" :disabled="busy" @click="openAddForm()">
        <Icon name="plus" :size="14" />
        手工新增映射
      </button>
    </div>
    <div v-else class="provider-mapping-table">
      <div class="provider-mapping-head" aria-hidden="true">
        <span>上游模型</span>
        <span>对外 ID</span>
        <span>模式</span>
        <span>来源</span>
      </div>
      <div class="provider-mapping-rows">
        <div
          v-for="row in filteredRows"
          :key="row.upstreamModel"
          class="provider-mapping-row"
          :class="{
            'is-custom': mappingDraftFor(row.upstreamModel).mode !== 'auto',
            'is-dirty': dirtyModels.has(row.upstreamModel),
            'is-conflict': conflictingModels.has(row.upstreamModel),
          }"
          :data-provider-mapping-model="row.upstreamModel"
        >
          <div class="provider-mapping-upstream">
            <span class="mono provider-model-id" :title="row.upstreamModel">{{ row.upstreamModel }}</span>
            <span
              v-if="!baselineByModel.has(row.upstreamModel)"
              class="badge info small"
              title="新增映射草稿；保存后写入共享目录，账号目录出现该模型时自动激活"
            >新增</span>
            <span
              v-else-if="!row.catalogActive"
              class="badge neutral small"
              title="当前所有账号目录均未包含该模型，保留历史人工映射"
            >休眠</span>
            <span
              v-else-if="!row.enabled"
              class="badge neutral small"
              title="当前没有启用账号授权此模型；已有映射保留，不参与列表模型统计"
            >未启用</span>
          </div>

          <div class="provider-mapping-public" data-label="对外 ID">
            <OAuthMappingInput
              v-if="mappingDraftFor(row.upstreamModel).mode === 'manual'"
              :model-value="mappingDraftFor(row.upstreamModel).canonicalModelId"
              :upstream-model="row.upstreamModel"
              :options="mappingCandidates"
              :disabled="busy"
              inline
              picker-text="候选"
              @update:model-value="changeManual(row.upstreamModel, $event)"
            />
            <span
              v-else
              class="provider-mapping-public-readout"
              :class="{ 'is-aliased': mappingPublicId(row.upstreamModel) !== row.upstreamModel }"
              :title="mappingPublicId(row.upstreamModel)"
            >
              <span v-if="mappingPublicId(row.upstreamModel) !== row.upstreamModel" class="provider-mapping-arrow" aria-hidden="true">→</span>
              <span class="mono">{{ mappingPublicId(row.upstreamModel) }}</span>
            </span>
            <span v-if="conflictingModels.has(row.upstreamModel)" class="provider-conflict-baseline small">
              最新共享值：{{ row.publicModelId }}（{{ row.mode }}；来源 {{ rawSourceLabel(row.mappingSource) }}）
            </span>
          </div>

          <div class="provider-mapping-mode">
            <select
              class="provider-mode-select"
              :class="'is-' + mappingDraftFor(row.upstreamModel).mode"
              :value="mappingDraftFor(row.upstreamModel).mode"
              :aria-label="row.upstreamModel + ' 映射模式'"
              :disabled="busy"
              @change="changeMode(row.upstreamModel, ($event.target as HTMLSelectElement).value as OAuthModelMappingMode)"
            >
              <option value="auto">自动</option>
              <option value="manual">手工</option>
              <option value="upstream">使用上游 ID</option>
            </select>
          </div>

          <div class="provider-mapping-source-cell">
            <span
              class="provider-source-text"
              :class="'is-' + sourceMeta(row.upstreamModel).tone"
              :title="sourceMeta(row.upstreamModel).title"
            >
              <span v-if="sourceMeta(row.upstreamModel).tone === 'dirty'" class="provider-source-dot" aria-hidden="true"></span>
              {{ sourceMeta(row.upstreamModel).label }}
            </span>
            <div
              v-if="mappingDraftFor(row.upstreamModel).mode !== 'auto' || dirtyModels.has(row.upstreamModel)"
              class="provider-row-actions"
            >
              <button
                v-if="mappingDraftFor(row.upstreamModel).mode !== 'auto'"
                type="button"
                class="provider-row-action"
                :disabled="busy"
                @click="changeMode(row.upstreamModel, 'auto')"
              >恢复自动</button>
              <button
                v-if="dirtyModels.has(row.upstreamModel)"
                type="button"
                class="provider-row-action"
                :disabled="busy"
                @click="discardDraft(row.upstreamModel)"
              >撤销</button>
            </div>
          </div>
        </div>

        <div v-if="filteredRows.length === 0" class="provider-mappings-empty is-in-table">
          <p class="muted small">
            <template v-if="query.trim()">
              没有匹配「{{ query.trim() }}」的{{ filterMode === 'custom' ? '自定义' : '' }}模型。
            </template>
            <template v-else-if="filterMode === 'custom'">
              当前没有自定义或待保存的映射。
            </template>
          </p>
          <div class="provider-empty-actions">
            <button v-if="filterMode === 'custom'" type="button" class="button ghost mini" @click="filterMode = 'all'">
              查看全部
            </button>
            <button
              v-if="query.trim() && !baselineByModel.has(query.trim()) && !dirtyModels.has(query.trim())"
              type="button"
              class="button subtle mini"
              :disabled="busy"
              @click="addFromQuery"
            >
              新增「{{ query.trim() }}」为手工映射
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="provider-mappings-footer">
      <div class="provider-mappings-footer-status muted small">
        <template v-if="dirtyCount > 0">
          <span class="provider-dirty-dot" aria-hidden="true"></span>
          <span>{{ dirtyCount }} 项未保存修改</span>
        </template>
        <template v-else-if="baseline">
          <span>所有映射已同步 · 共 {{ allModels.length }} 项</span>
        </template>
      </div>
      <div class="provider-mappings-footer-actions">
        <button v-if="dirtyCount > 0" type="button" class="button ghost" :disabled="busy" @click="discardAllDrafts">
          放弃全部草稿
        </button>
        <button
          type="button"
          class="button primary"
          :disabled="busy || savableDraftCount === 0 || !!loadError || (hasConflict && !conflictConfirmed)"
          @click="save"
        >
          {{ saving ? '保存中…' : savableDraftCount > 0 ? `保存映射修改（${savableDraftCount}）` : '保存映射修改' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.provider-mappings-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  gap: 12px;
}

.provider-mappings-intro {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.provider-mappings-context {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  min-width: 0;
  flex: 1 1 320px;
}

.provider-mappings-desc {
  line-height: 1.45;
}

.provider-mappings-pills {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.provider-meta-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border: 1px solid var(--glass-line);
  border-radius: 999px;
  background: var(--glass-bg-soft);
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
}

.provider-mappings-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.provider-mappings-notices {
  display: grid;
  flex: 0 0 auto;
  gap: 8px;
}

.provider-mappings-notices p,
.provider-mappings-notices .callout {
  margin: 0;
}

.provider-candidate-note {
  padding: 0 2px;
  line-height: 1.45;
}

.provider-conflict-confirm {
  margin-top: 8px;
}

.provider-mappings-toolbar {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.provider-mapping-search {
  display: flex;
  flex: 1 1 220px;
  align-items: center;
  gap: 7px;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  background: var(--glass-field);
  color: var(--muted);
  transition: border-color 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}

.provider-mapping-search:focus-within {
  border-color: var(--brand);
  background: var(--glass-field-strong);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 12%, transparent);
}

.provider-mapping-search input {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 12.5px;
}

.provider-mapping-search input::placeholder {
  color: var(--muted);
  opacity: 0.78;
}

.provider-search-clear {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.provider-search-clear:hover {
  background: color-mix(in srgb, var(--ink) 8%, transparent);
  color: var(--ink);
}

.provider-filter-group {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  background: var(--glass-bg-soft);
}

.provider-filter-chip {
  height: 26px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.14s ease, background-color 0.14s ease, border-color 0.14s ease;
}

.provider-filter-chip:hover:not(.is-active) {
  color: var(--ink);
}

.provider-filter-chip.is-active {
  border-color: var(--glass-line);
  background: var(--glass-field-strong);
  color: var(--ink);
  font-weight: 600;
}

.provider-filter-chip.is-dormant.is-active {
  border-color: color-mix(in srgb, var(--brand) 25%, var(--glass-line));
  background: var(--info-soft);
  color: var(--info);
}

.provider-add-toggle {
  flex: 0 0 auto;
  height: 34px;
  min-height: 34px;
  padding: 0 11px;
  border-radius: 10px;
}

.provider-mapping-add-bar {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: minmax(0, 1.4fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--glass-line);
  border-radius: 12px;
  background: color-mix(in srgb, var(--glass-field) 82%, transparent);
}

.provider-mapping-add-input {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--glass-line);
  border-radius: 9px;
  background: var(--glass-field-strong);
  color: var(--muted);
}

.provider-mapping-add-input:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 12%, transparent);
}

.provider-mapping-add-input input {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-family: ui-monospace, 'SFMono-Regular', 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.provider-mapping-add-mode {
  height: auto;
  min-height: 34px;
  padding: 6px 28px 6px 10px;
  border-radius: 9px;
  font-size: 12.5px;
  line-height: 1.4;
}

.provider-mapping-add-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.provider-mapping-add-actions .button.mini {
  min-height: 34px;
  height: 34px;
  padding: 0 12px;
  border-radius: 9px;
}

.provider-mappings-empty {
  display: grid;
  flex: 1 1 auto;
  place-content: center;
  justify-items: center;
  gap: 10px;
  min-height: 180px;
  padding: 28px 18px;
  border: 1px dashed var(--glass-line);
  border-radius: 14px;
  background: color-mix(in srgb, var(--glass-field) 55%, transparent);
  text-align: center;
}

.provider-mappings-empty p {
  margin: 0;
  max-width: 440px;
  line-height: 1.55;
}

.provider-mappings-empty.is-in-table {
  min-height: 150px;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.provider-empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.provider-mapping-table {
  --provider-mapping-columns: minmax(190px, 1.15fr) minmax(240px, 1.45fr) 124px minmax(120px, 0.72fr);
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  border: 1px solid var(--glass-line);
  border-radius: 14px;
  background: var(--glass-field);
  overflow: hidden;
}

.provider-mapping-head,
.provider-mapping-row {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: var(--provider-mapping-columns);
  gap: 14px;
  align-items: center;
  padding: 10px 16px;
}

.provider-mapping-head {
  flex: 0 0 auto;
  border-bottom: 1px solid var(--glass-line);
  background: color-mix(in srgb, var(--surface-head) 68%, transparent);
  color: var(--muted);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.provider-mapping-rows {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.provider-mapping-row {
  position: relative;
  min-height: 50px;
  transition: background-color 0.12s ease;
}

.provider-mapping-row + .provider-mapping-row {
  border-top: 1px solid color-mix(in srgb, var(--glass-line) 70%, transparent);
}

.provider-mapping-row:hover {
  background: color-mix(in srgb, var(--glass-field-strong) 58%, transparent);
}

.provider-mapping-row.is-custom {
  background: color-mix(in srgb, var(--info-soft) 24%, transparent);
  box-shadow: inset 2px 0 0 color-mix(in srgb, var(--brand) 48%, transparent);
}

.provider-mapping-row.is-dirty {
  background: color-mix(in srgb, var(--warning-soft) 32%, transparent);
  box-shadow: inset 2px 0 0 var(--warning-fill, #f59e0b);
}

.provider-mapping-row.is-conflict {
  background: color-mix(in srgb, var(--danger-soft) 34%, transparent);
  box-shadow: inset 2px 0 0 var(--danger-fill, #f87171);
}

.provider-mapping-upstream {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.provider-model-id {
  min-width: 0;
  font-size: 12.5px;
  font-weight: 550;
  color: var(--ink);
  overflow-wrap: anywhere;
}

.provider-mapping-upstream .badge {
  flex: 0 0 auto;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 600;
}

.provider-mapping-public {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: 4px;
}

.provider-mapping-public-readout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.45;
}

.provider-mapping-public-readout.is-aliased {
  color: var(--ink);
}

.provider-mapping-public-readout .mono {
  min-width: 0;
  overflow-wrap: anywhere;
}

.provider-mapping-arrow {
  flex: 0 0 auto;
  color: var(--muted);
  opacity: 0.72;
  font-size: 12px;
}

.provider-conflict-baseline {
  color: var(--danger);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.provider-mapping-mode {
  min-width: 0;
}

.provider-mode-select {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  height: auto;
  min-height: 30px;
  padding: 5px 26px 5px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  background-position: right 8px center;
  background-size: 13px 13px;
}

.provider-mode-select.is-auto {
  border-color: transparent;
  background-color: color-mix(in srgb, var(--ink) 4.5%, transparent);
  color: var(--muted);
}

.provider-mode-select.is-auto:hover:not(:disabled),
.provider-mode-select.is-auto:focus {
  border-color: var(--glass-line);
  background-color: var(--glass-field-strong);
  color: var(--ink);
}

.provider-mode-select.is-manual {
  border-color: color-mix(in srgb, var(--brand) 24%, var(--glass-line));
  background-color: var(--info-soft);
  color: var(--info);
  font-weight: 600;
}

.provider-mode-select.is-upstream {
  border-color: var(--reasoning-line);
  background-color: var(--reasoning-soft);
  color: var(--reasoning);
  font-weight: 600;
}

.provider-mapping-source-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 3px;
  min-width: 0;
}

.provider-source-text {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  line-height: 1.35;
  color: var(--muted);
}

.provider-source-text.is-muted {
  opacity: 0.72;
}

.provider-source-text.is-normal {
  color: var(--ink-soft);
}

.provider-source-text.is-accent {
  color: var(--info);
  font-weight: 550;
}

.provider-source-text.is-dirty,
.provider-source-text.is-warning {
  color: var(--warning);
  font-weight: 600;
}

.provider-source-dot,
.provider-dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--warning-fill, #f59e0b);
  flex: 0 0 auto;
}

.provider-row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.provider-row-action {
  padding: 1px 6px;
  border: 0;
  border-radius: 6px;
  background: color-mix(in srgb, var(--ink) 5%, transparent);
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.45;
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease;
}

.provider-row-action:hover:not(:disabled) {
  background: var(--info-soft);
  color: var(--brand);
}

.provider-row-action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.provider-mappings-footer {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 14px;
  padding-top: 10px;
  border-top: 1px solid var(--glass-line);
}

.provider-mappings-footer-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 20px;
}

.provider-mappings-footer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 820px) {
  .provider-mapping-table {
    --provider-mapping-columns: minmax(150px, 1fr) minmax(200px, 1.3fr) 114px minmax(105px, 0.7fr);
  }

  .provider-mapping-head,
  .provider-mapping-row {
    gap: 10px;
    padding: 10px 12px;
  }
}

@media (max-width: 680px), (max-height: 560px) {
  .provider-mappings-panel {
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .provider-mapping-table {
    flex: none;
    overflow: visible;
  }

  .provider-mapping-rows {
    flex: none;
    overflow: visible;
  }

  .provider-mapping-search {
    flex: 1 1 100%;
  }
}

@media (max-width: 680px) {
  .provider-mapping-add-bar {
    grid-template-columns: minmax(0, 1fr);
  }

  .provider-mapping-head {
    display: none;
  }

  .provider-mapping-row {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'upstream mode'
      'public public'
      'source source';
    gap: 8px 10px;
    padding: 12px 14px;
    align-items: center;
  }

  .provider-mapping-upstream {
    grid-area: upstream;
  }

  .provider-mapping-mode {
    grid-area: mode;
    min-width: 112px;
  }

  .provider-mapping-public {
    grid-area: public;
  }

  .provider-mapping-public::before {
    content: attr(data-label);
    display: block;
    font-size: 11px;
    color: var(--muted);
    margin-bottom: 2px;
  }

  .provider-mapping-source-cell {
    grid-area: source;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }

  .provider-mappings-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .provider-mappings-footer-actions {
    justify-content: flex-end;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .provider-mapping-table,
  .provider-mapping-add-bar,
  .provider-mapping-search,
  .provider-mapping-add-input {
    background: var(--panel);
    border-color: var(--line);
  }

  .provider-filter-group {
    background: var(--surface-head);
    border-color: var(--line);
  }

  .provider-filter-chip.is-active {
    background: var(--panel);
    border-color: var(--line);
  }

  .provider-mapping-head {
    background: var(--surface-head);
    border-bottom-color: var(--line);
  }

  .provider-mapping-row + .provider-mapping-row {
    border-top-color: var(--line);
  }
}
</style>
