<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { ApiError } from '../types/api'
import type { ModelRouteCredential, ModelRouteDetail, ModelRouteIdentity, ModelRouteSummary } from '../types/api'
import { notify } from '../toast'
import ErrorState from './ErrorState.vue'
import Icon from './Icon.vue'

const pageSize = 100
const models = ref<ModelRouteSummary[]>([])
const total = ref(0)
const offset = ref(0)
const query = ref('')
const listLoading = ref(true)
const listError = ref('')
const selectedModelId = ref('')
const detail = ref<ModelRouteDetail | null>(null)
const entries = ref<ModelRouteCredential[]>([])
const detailLoading = ref(false)
const detailError = ref('')
const saving = ref(false)
const saveError = ref('')
const savedNotice = ref('')
const announcement = ref('')
const credentialQuery = ref('')
const conflict = ref(false)
const conflictLatest = ref<ModelRouteDetail | null>(null)
const conflictLoading = ref(false)
const conflictError = ref('')
const draggedEntryKey = ref('')
const dragOverEntryKey = ref('')
let listGeneration = 0
let detailGeneration = 0
let disposed = false
let searchTimer: ReturnType<typeof setTimeout> | undefined
const root = ref<HTMLElement | null>(null)

function identity(entry: ModelRouteIdentity): ModelRouteIdentity {
  return { channelId: entry.channelId, credentialType: entry.credentialType, credentialId: entry.credentialId }
}
function entryKey(entry: ModelRouteIdentity): string {
  return JSON.stringify([entry.channelId, entry.credentialType, entry.credentialId])
}
function orderSnapshot(items: ModelRouteIdentity[]): string {
  return JSON.stringify(items.map(identity))
}
function cloneEntries(items: ModelRouteCredential[]): ModelRouteCredential[] {
  return items.map(entry => ({ ...entry, upstreamModels: [...entry.upstreamModels], protocols: [...entry.protocols] }))
}
function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}
function credentialLabel(entry: ModelRouteCredential): string {
  return `${entry.channelName || entry.channelId} / ${entry.credentialName || entry.credentialId}`
}
const unavailableReasons: Record<string, string> = {
  channel_missing: '渠道已删除或不存在。',
  credential_missing: '凭据已删除或不存在。',
  channel_disabled: '渠道已停用，请先启用渠道。',
  credential_disabled: '凭据已停用或需要重新授权。',
  model_unsupported: '凭据未授权此模型，或模型映射已变更。',
  protocol_unsupported: '渠道与凭据没有共同支持的协议。',
}
function unavailableReason(entry: ModelRouteCredential): string {
  return entry.reason ? unavailableReasons[entry.reason] || entry.reason : '凭据当前不可用，请在渠道或订阅账号中检查配置。'
}
function modelStatus(model: ModelRouteSummary): string {
  if (!model.configured) return '待配置'
  return model.entryCount ? '已配置' : '无路由凭据'
}

const sortedModels = computed(() => [...models.value].sort((a, b) => a.modelId.localeCompare(b.modelId)))
const dirty = computed(() => !!detail.value && orderSnapshot(entries.value) !== orderSnapshot(detail.value.entries))
const editorBusy = computed(() => detailLoading.value || saving.value || conflictLoading.value)
const selectedKeys = computed(() => new Set(entries.value.map(entryKey)))
const availableCredentials = computed(() => {
  const search = credentialQuery.value.trim().toLowerCase()
  return (detail.value?.availableCredentials ?? [])
    .filter(entry => !selectedKeys.value.has(entryKey(entry)))
    .filter(entry => !search || [entry.channelName, entry.credentialName, entry.channelId, entry.credentialId, ...entry.upstreamModels, ...entry.protocols].join(' ').toLowerCase().includes(search))
})
const remainingCount = computed(() => (detail.value?.availableCredentials ?? []).filter(entry => !selectedKeys.value.has(entryKey(entry))).length)
const canSave = computed(() => !!detail.value && (dirty.value || !detail.value.configured) && !editorBusy.value && !conflict.value)

function clearDrag() {
  draggedEntryKey.value = ''
  dragOverEntryKey.value = ''
}

function acceptDetail(value: ModelRouteDetail) {
  clearDrag()
  detail.value = value
  entries.value = cloneEntries(value.entries)
  conflict.value = false
  conflictLatest.value = null
  conflictError.value = ''
  saveError.value = ''
  savedNotice.value = ''
}

async function loadModels() {
  const generation = ++listGeneration
  listLoading.value = true
  listError.value = ''
  try {
    const page = await api.listModelRoutes({ q: query.value, offset: offset.value, limit: pageSize })
    if (disposed || generation !== listGeneration) return
    models.value = page.items
    total.value = page.total
    offset.value = page.offset
    if (!selectedModelId.value && page.items.length) {
      void selectModel(sortedModels.value[0].modelId)
    }
  } catch (cause) {
    if (!disposed && generation === listGeneration) listError.value = errorMessage(cause)
  } finally {
    if (!disposed && generation === listGeneration) listLoading.value = false
  }
}

async function loadDetail(modelId: string) {
  const generation = ++detailGeneration
  detailLoading.value = true
  detailError.value = ''
  try {
    const value = await api.getModelRoute(modelId)
    if (disposed || generation !== detailGeneration || modelId !== selectedModelId.value) return
    acceptDetail(value)
  } catch (cause) {
    if (!disposed && generation === detailGeneration) detailError.value = errorMessage(cause)
  } finally {
    if (!disposed && generation === detailGeneration) detailLoading.value = false
  }
}

async function selectModel(modelId: string) {
  if (saving.value || conflictLoading.value || selectedModelId.value === modelId) return
  if (dirty.value && !window.confirm('当前模型的路由顺序尚未保存，切换模型将丢弃修改。确定切换吗？')) return
  clearDrag()
  selectedModelId.value = modelId
  detail.value = null
  entries.value = []
  credentialQuery.value = ''
  conflict.value = false
  conflictLatest.value = null
  saveError.value = ''
  savedNotice.value = ''
  await loadDetail(modelId)
}

function changePage(delta: number) {
  if (listLoading.value) return
  offset.value = Math.max(0, offset.value + delta * pageSize)
  void loadModels()
}

function changed() {
  saveError.value = ''
  savedNotice.value = ''
}

async function focusEntry(key: string, action: string) {
  await nextTick()
  const row = [...(root.value?.querySelectorAll<HTMLElement>('[data-route-entry]') ?? [])].find(node => node.dataset.routeEntry === key)
  const button = row?.querySelector<HTMLButtonElement>(`button[data-action="${action}"]:not(:disabled)`)
    ?? row?.querySelector<HTMLButtonElement>('button:not(:disabled)')
  button?.focus()
}

function moveEntry(target: ModelRouteIdentity, delta: -1 | 1) {
  if (editorBusy.value) return
  const index = entries.value.findIndex(entry => entryKey(entry) === entryKey(target))
  if (index < 0) return
  const destination = index + delta
  if (destination < 0 || destination >= entries.value.length) return
  const [entry] = entries.value.splice(index, 1)
  entries.value.splice(destination, 0, entry)
  changed()
  announcement.value = `${credentialLabel(entry)} 已移至第 ${destination + 1} 位，共 ${entries.value.length} 个凭据。`
  void focusEntry(entryKey(entry), delta === -1 ? 'up' : 'down')
}

function dragStart(event: DragEvent, target: ModelRouteIdentity) {
  if (editorBusy.value) {
    event.preventDefault()
    return
  }
  const key = entryKey(target)
  draggedEntryKey.value = key
  dragOverEntryKey.value = key
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', key)
  }
}

function dragOver(event: DragEvent, target: ModelRouteIdentity) {
  if (editorBusy.value || !draggedEntryKey.value) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dragOverEntryKey.value = entryKey(target)
}

function dropEntry(event: DragEvent, target: ModelRouteIdentity) {
  if (editorBusy.value) {
    clearDrag()
    return
  }
  event.preventDefault()
  const sourceKey = draggedEntryKey.value
  const targetKey = entryKey(target)
  clearDrag()
  if (!sourceKey || sourceKey === targetKey) return
  const sourceIndex = entries.value.findIndex(entry => entryKey(entry) === sourceKey)
  const targetIndex = entries.value.findIndex(entry => entryKey(entry) === targetKey)
  if (sourceIndex < 0 || targetIndex < 0) return
  const [entry] = entries.value.splice(sourceIndex, 1)
  entries.value.splice(targetIndex, 0, entry)
  changed()
  announcement.value = `${credentialLabel(entry)} 已移至第 ${targetIndex + 1} 位，共 ${entries.value.length} 个凭据。`
  void focusEntry(sourceKey, 'up')
}

function addEntry(entry: ModelRouteCredential) {
  if (editorBusy.value || !entry.selectable || selectedKeys.value.has(entryKey(entry))) return
  entries.value.push(...cloneEntries([entry]))
  changed()
  announcement.value = `已添加 ${credentialLabel(entry)}，位于第 ${entries.value.length} 位。`
  void focusEntry(entryKey(entry), 'up')
}

function removeEntry(target: ModelRouteIdentity) {
  if (editorBusy.value) return
  const index = entries.value.findIndex(entry => entryKey(entry) === entryKey(target))
  if (index < 0) return
  const [entry] = entries.value.splice(index, 1)
  changed()
  announcement.value = `已移除 ${credentialLabel(entry)}。`
  const next = entries.value[Math.min(index, entries.value.length - 1)]
  if (next) void focusEntry(entryKey(next), 'remove')
  else nextTick(() => root.value?.querySelector<HTMLInputElement>('[aria-label="搜索可添加凭据"]')?.focus())
}

function resetDraft() {
  if (!detail.value || editorBusy.value) return
  if (dirty.value && !window.confirm('丢弃当前未保存的路由修改？')) return
  entries.value = cloneEntries(detail.value.entries)
  changed()
  announcement.value = '已恢复读取时的路由顺序。'
}

async function loadConflictLatest() {
  if (conflictLoading.value || !detail.value) return
  const modelId = selectedModelId.value
  const generation = detailGeneration
  conflictLoading.value = true
  conflictError.value = ''
  try {
    const value = await api.getModelRoute(modelId)
    if (disposed || generation !== detailGeneration || modelId !== selectedModelId.value) return
    conflictLatest.value = value
  } catch (cause) {
    if (!disposed && generation === detailGeneration) conflictError.value = errorMessage(cause)
  } finally {
    if (!disposed && generation === detailGeneration) conflictLoading.value = false
  }
}

function useLatestOrder() {
  if (!conflictLatest.value || editorBusy.value) return
  acceptDetail(conflictLatest.value)
  announcement.value = '已载入服务器最新路由顺序，原草稿已丢弃。'
}

function keepDraftOnLatestRevision() {
  const latest = conflictLatest.value
  if (!latest || editorBusy.value) return
  // Preserve order and stored references; refresh availability metadata by identity.
  const metadata = new Map([...latest.entries, ...latest.availableCredentials].map(entry => [entryKey(entry), entry]))
  entries.value = entries.value.map(entry => metadata.get(entryKey(entry)) ?? { ...entry, selectable: false, reason: '该凭据已不在最新目录中，可移除后保存。' })
  detail.value = latest
  conflict.value = false
  conflictLatest.value = null
  conflictError.value = ''
  saveError.value = ''
  announcement.value = '已保留草稿并采用最新版本，请检查顺序后再次保存。'
}

async function save() {
  if (!canSave.value || !detail.value) return
  const modelId = selectedModelId.value
  const generation = detailGeneration
  saving.value = true
  saveError.value = ''
  savedNotice.value = ''
  try {
    const value = await api.updateModelRoute({ modelId, expectedRevision: detail.value.revision, entries: entries.value.map(identity) })
    if (disposed || generation !== detailGeneration) return
    acceptDetail(value)
    savedNotice.value = '模型路由已保存'
    notify('模型路由已保存')
    void loadModels()
  } catch (cause) {
    if (disposed || generation !== detailGeneration) return
    if (cause instanceof ApiError && cause.status === 409) {
      conflict.value = true
      await loadConflictLatest()
    } else {
      saveError.value = errorMessage(cause)
    }
  } finally {
    if (!disposed && generation === detailGeneration) saving.value = false
  }
}

function canLeave(): boolean {
  if (editorBusy.value) return false
  return !dirty.value || window.confirm('当前模型的路由顺序尚未保存，离开页面将丢弃修改。确定离开吗？')
}

defineExpose({ canLeave })

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(editorBusy, busy => { if (busy) clearDrag() })
watch(query, () => {
  clearTimeout(searchTimer)
  // Invalidate a previous page immediately, including during the debounce interval.
  listGeneration++
  listLoading.value = true
  offset.value = 0
  searchTimer = setTimeout(() => { void loadModels() }, 250)
})
onMounted(() => {
  void loadModels()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  disposed = true
  listGeneration++
  detailGeneration++
  clearTimeout(searchTimer)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section ref="root" class="model-routes-view" aria-label="模型路由配置">
    <aside class="panel route-models" aria-labelledby="route-models-heading">
      <div class="route-panel-head">
        <div><h2 id="route-models-heading">对外模型</h2><p class="muted small">规范 ID 与自定义对外 ID</p></div>
        <button type="button" class="icon-button" aria-label="刷新模型列表" :disabled="listLoading" @click="loadModels"><Icon name="refresh" :size="16" /></button>
      </div>
      <label class="search-box route-model-search">
        <Icon name="search" :size="16" />
        <input v-model="query" type="search" aria-label="搜索对外模型" placeholder="搜索对外模型 ID" autocomplete="off" />
      </label>
      <ErrorState v-if="listError" :message="listError" title="模型列表读取失败" :busy="listLoading" @retry="loadModels" />
      <p v-if="listLoading" class="route-empty muted" role="status">正在读取模型…</p>
      <template v-else-if="!listError">
        <p v-if="!models.length" class="route-empty muted" role="status">{{ query.trim() ? '没有匹配的模型，请调整搜索。' : '还没有对外模型，请先在渠道或订阅账号中授权模型。' }}</p>
        <ul v-else class="route-model-list" aria-label="对外模型列表">
          <li v-for="model in sortedModels" :key="model.modelId">
            <button type="button" class="route-model" :class="{ 'is-selected': selectedModelId === model.modelId }" :aria-current="selectedModelId === model.modelId ? 'true' : undefined" :disabled="saving || conflictLoading" :data-model-id="model.modelId" @click="selectModel(model.modelId)">
              <strong>{{ model.modelId }}</strong>
              <span class="route-model-meta"><span class="badge" :class="!model.configured || !model.entryCount ? 'warning' : 'neutral'">{{ modelStatus(model) }}</span><span>{{ model.entryCount }} 个已选 · {{ model.availableCount }} 个可选</span></span>
            </button>
          </li>
        </ul>
        <div class="route-pagination">
          <span class="muted small">共 {{ total }} 个<span v-if="total"> · {{ offset + 1 }}–{{ offset + models.length }}</span></span>
          <div><button type="button" class="button ghost mini" aria-label="上一页模型" :disabled="offset === 0" @click="changePage(-1)">上一页</button><button type="button" class="button ghost mini" aria-label="下一页模型" :disabled="offset + models.length >= total" @click="changePage(1)">下一页</button></div>
        </div>
      </template>
    </aside>

    <section class="panel route-editor" aria-labelledby="route-editor-heading" :aria-busy="detailLoading || saving">
      <div class="route-panel-head">
        <div><h2 id="route-editor-heading">{{ selectedModelId || '凭据顺序' }}</h2><p class="muted small">按列表顺序尝试凭据；同一凭据的多个上游模型一起排序。</p></div>
        <span v-if="detail" class="badge neutral">版本 {{ detail.revision }}</span>
      </div>
      <p v-if="!selectedModelId" class="route-empty muted">选择一个对外模型，配置跨渠道的凭据顺序。</p>
      <p v-else-if="detailLoading" class="route-empty muted" role="status">正在读取路由配置…</p>
      <ErrorState v-else-if="detailError" title="路由配置读取失败" :message="detailError" @retry="loadDetail(selectedModelId)" />
      <template v-else-if="detail">
        <div v-if="conflict" class="callout warning route-conflict" role="alert">
          <strong>路由配置已被其他管理员更新，本次未保存。</strong>
          <p>你的草稿已保留。请核对服务器最新顺序，再选择载入最新配置或保留草稿后重试。</p>
          <p v-if="conflictLoading" role="status">正在读取最新版本…</p>
          <p v-if="conflictError" class="text-danger">{{ conflictError }}</p>
          <template v-if="conflictLatest">
            <p><strong>服务器版本 {{ conflictLatest.revision }}</strong></p>
            <ol v-if="conflictLatest.entries.length" class="route-conflict-order"><li v-for="entry in conflictLatest.entries" :key="entryKey(entry)">{{ credentialLabel(entry) }}</li></ol>
            <p v-else>服务器当前未选择凭据。</p>
            <div class="route-inline-actions"><button type="button" class="button subtle mini" :disabled="editorBusy" @click="useLatestOrder">载入最新顺序，丢弃草稿</button><button type="button" class="button ghost mini" :disabled="editorBusy" @click="keepDraftOnLatestRevision">已核对，保留草稿并采用版本 {{ conflictLatest.revision }}</button></div>
          </template>
          <button v-else-if="!conflictLoading" type="button" class="button ghost mini" @click="loadConflictLatest">重新读取最新版本</button>
        </div>

        <div class="route-section-head"><h3>已选凭据 <span class="muted small">{{ entries.length }} 个</span></h3><span v-if="dirty" class="badge warning">未保存</span></div>
        <p id="route-order-help" class="muted small">使用上移、下移按钮或拖动条目调整顺序。不可用凭据会保留位置，可移除或调整。可用表示配置就绪，请求仍受冷却、额度和并发限制。</p>
        <div v-if="!entries.length" class="route-empty route-empty-order">
          <Icon name="list" :size="25" />
          <strong>{{ !detail.configured ? '待配置，请添加凭据' : '尚未选择路由凭据' }}</strong>
          <p>从下方添加凭据。空列表不会自动使用其他凭据，模型将无法接收请求。</p>
        </div>
        <ol v-else class="route-entry-list" aria-label="已选凭据顺序" aria-describedby="route-order-help">
          <li v-for="(entry, index) in entries" :key="entryKey(entry)" class="route-entry" :class="{ 'is-unavailable': !entry.selectable, 'is-dragging': draggedEntryKey === entryKey(entry), 'is-drag-over': dragOverEntryKey === entryKey(entry) && draggedEntryKey !== entryKey(entry) }" :data-route-entry="entryKey(entry)" :draggable="!editorBusy" @dragstart="dragStart($event, entry)" @dragover="dragOver($event, entry)" @drop="dropEntry($event, entry)" @dragend="clearDrag">
            <span class="route-position" aria-hidden="true">{{ index + 1 }}</span>
            <div class="route-credential-copy">
              <div class="route-credential-title"><strong>{{ entry.credentialName || entry.credentialId }}</strong><span class="badge neutral">{{ entry.credentialType === 'oauth' ? '订阅账号' : '静态密钥' }}</span><span class="badge" :class="entry.selectable ? 'success' : 'warning'">{{ entry.selectable ? '可用' : '不可用' }}</span></div>
              <p class="muted small">{{ entry.channelName || entry.channelId }}</p>
              <p class="route-upstream small">上游模型：{{ entry.upstreamModels.join('、') || '无授权模型' }}</p>
              <p class="muted small">协议：{{ entry.protocols.join(' / ') || '未配置' }}</p>
              <p v-if="!entry.selectable" class="route-reason small">{{ unavailableReason(entry) }}</p>
            </div>
            <div class="route-entry-actions" :aria-label="credentialLabel(entry) + '的排序与移除操作'" role="group">
              <button type="button" class="icon-button" data-action="up" :aria-label="'上移 ' + credentialLabel(entry)" title="上移" :disabled="editorBusy || index === 0" @click="moveEntry(entry, -1)"><Icon name="up" :size="18" /></button>
              <button type="button" class="icon-button" data-action="down" :aria-label="'下移 ' + credentialLabel(entry)" title="下移" :disabled="editorBusy || index === entries.length - 1" @click="moveEntry(entry, 1)"><Icon name="down" :size="18" /></button>
              <button type="button" class="icon-button danger" data-action="remove" :aria-label="'移除 ' + credentialLabel(entry)" title="移除" :disabled="editorBusy" @click="removeEntry(entry)"><Icon name="trash" :size="16" /></button>
            </div>
          </li>
        </ol>

        <div class="route-add-section">
          <div class="route-section-head"><h3>添加凭据 <span class="muted small">{{ remainingCount }} 个未选择</span></h3></div>
          <label class="search-box route-credential-search"><Icon name="search" :size="16" /><input v-model="credentialQuery" type="search" aria-label="搜索可添加凭据" placeholder="搜索渠道、凭据或上游模型" autocomplete="off" /></label>
          <p v-if="!availableCredentials.length" class="muted small" role="status">{{ credentialQuery.trim() ? '没有匹配的凭据。' : remainingCount ? '没有匹配的凭据。' : '没有更多凭据。可在渠道或订阅账号中配置并授权此模型。' }}</p>
          <ul v-else class="route-available-list" aria-label="可添加凭据">
            <li v-for="entry in availableCredentials" :key="entryKey(entry)" class="route-available" :data-available-credential="entryKey(entry)">
              <div class="route-credential-copy">
                <div class="route-credential-title"><strong>{{ entry.credentialName || entry.credentialId }}</strong><span class="badge neutral">{{ entry.credentialType === 'oauth' ? '订阅账号' : '静态密钥' }}</span><span v-if="!entry.selectable" class="badge warning">不可用</span></div>
                <p class="muted small">{{ entry.channelName || entry.channelId }}</p>
                <p class="route-upstream small">上游模型：{{ entry.upstreamModels.join('、') || '无授权模型' }}</p>
                <p class="muted small">协议：{{ entry.protocols.join(' / ') || '未配置' }}</p>
                <p v-if="!entry.selectable" class="route-reason small">{{ unavailableReason(entry) }}</p>
              </div>
              <button type="button" class="button subtle mini" :aria-label="'添加 ' + credentialLabel(entry)" :title="entry.selectable ? '添加到顺序末尾' : unavailableReason(entry)" :disabled="editorBusy || !entry.selectable" @click="addEntry(entry)"><Icon name="plus" :size="15" />添加</button>
            </li>
          </ul>
        </div>

        <div v-if="saveError" class="callout danger" role="alert">{{ saveError }} 草稿已保留，可重试保存。</div>
        <div v-if="!entries.length && (dirty || !detail.configured)" class="callout warning" role="status">保存空列表后，该模型将停止接收请求。请确认不选择任何凭据。</div>
        <div class="route-save-bar">
          <span class="small" role="status">{{ savedNotice || (dirty ? '有未保存修改' : !detail.configured ? '模型待配置，可添加凭据或保存空列表' : '当前配置未修改') }}</span>
          <div class="route-inline-actions"><button type="button" class="button ghost" :disabled="!dirty || editorBusy || conflict" @click="resetDraft">撤销修改</button><button type="button" class="button primary" :disabled="!canSave" @click="save">{{ saving ? '保存中…' : '保存路由' }}</button></div>
        </div>
      </template>
    </section>
    <span class="sr-only" aria-live="polite" aria-atomic="true">{{ announcement }}</span>
  </section>
</template>

<style scoped>
.model-routes-view { display: grid; grid-template-columns: minmax(230px, 300px) minmax(0, 1fr); align-items: start; gap: 18px; min-width: 0; }
.route-models, .route-editor { min-width: 0; padding: 18px; }
.route-models { position: sticky; top: 18px; }
.route-panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; min-width: 0; margin-bottom: 16px; }
.route-panel-head > div { min-width: 0; }
.route-panel-head h2 { margin: 0 0 5px; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.route-panel-head p, .route-credential-copy p { margin: 3px 0 0; line-height: 1.6; overflow-wrap: anywhere; }
.route-model-search, .route-credential-search { width: 100%; min-width: 0; }
.route-model-search input, .route-credential-search input { width: 0; min-width: 0; flex: 1; }
.route-model-list, .route-entry-list, .route-available-list { list-style: none; padding: 0; }
.route-model-list { display: grid; gap: 4px; max-height: min(60vh, 680px); margin: 12px -6px; overflow: auto; }
.route-model { display: grid; gap: 7px; width: 100%; padding: 11px 10px; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
.route-model strong { overflow-wrap: anywhere; font-size: 12px; font-weight: 650; }
.route-model:hover, .route-model.is-selected { background: var(--glass-field); border-color: var(--glass-line); }
.route-model.is-selected { border-left: 3px solid var(--brand); padding-left: 8px; }
.route-model-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 8px; font-size: 11px; color: var(--muted); }
.route-model-meta .badge, .route-credential-title .badge { margin: 0; font-size: 10px; }
.route-pagination { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px; border-top: 1px solid var(--glass-line); padding-top: 12px; }
.route-pagination > div, .route-inline-actions, .route-entry-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.route-empty { padding: 24px 6px; font-size: 13px; line-height: 1.8; }
.route-empty-order { display: grid; justify-items: center; gap: 8px; margin: 14px 0; padding: 24px 14px; border: 1px dashed var(--line-strong); border-radius: 10px; color: var(--muted); text-align: center; }
.route-empty-order strong { color: var(--ink); }
.route-empty-order p { max-width: 44ch; margin: 0; }
.route-section-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.route-section-head h3 { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0; font-size: 14px; }
.route-section-head + p { margin: 8px 0 12px; line-height: 1.6; }
.route-entry-list { display: grid; gap: 8px; margin: 0; }
.route-entry { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 14px 12px; border: 1px solid var(--glass-line); border-radius: 10px; background: var(--glass-field); cursor: grab; }
.route-entry:active { cursor: grabbing; }
.route-entry.is-dragging { opacity: .58; }
.route-entry.is-drag-over { border-color: var(--brand); box-shadow: 0 0 0 2px color-mix(in srgb, var(--brand) 20%, transparent); }
.route-entry.is-unavailable { border-left: 3px solid var(--warning); padding-left: 10px; }
.route-position { display: grid; place-items: center; width: 26px; height: 26px; border: 1px solid var(--glass-line); border-radius: 50%; color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.route-credential-copy { min-width: 0; }
.route-credential-title { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-height: 26px; }
.route-credential-title strong { font-size: 13px; overflow-wrap: anywhere; }
.route-upstream { color: var(--ink); }
.route-reason { color: var(--warning); }
.route-entry-actions { flex-wrap: nowrap; }
.route-entry-actions .icon-button { width: 34px; height: 34px; }
.route-add-section { display: grid; gap: 12px; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--glass-line); }
.route-available-list { display: grid; max-height: 420px; overflow: auto; margin: 0; }
.route-available { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--glass-line); }
.route-available:first-child { padding-top: 0; }
.route-available > .button { flex: 0 0 auto; }
.route-save-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--glass-line); }
.route-save-bar > span { color: var(--muted); }
.route-conflict { margin-bottom: 18px; }
.route-conflict p { line-height: 1.7; }
.route-conflict-order { padding-left: 22px; overflow-wrap: anywhere; line-height: 1.8; }
.route-model:focus-visible, .route-entry-actions button:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.route-model:disabled { cursor: wait; opacity: .65; }
.route-entry-actions button:disabled { opacity: .4; cursor: not-allowed; }
.route-entry[draggable="false"] { cursor: default; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 1000px) { .model-routes-view { grid-template-columns: minmax(210px, 260px) minmax(0, 1fr); gap: 12px; } .route-entry { grid-template-columns: 26px minmax(0, 1fr); } .route-entry-actions { grid-column: 2; justify-self: end; } }
@media (max-width: 760px) { .model-routes-view { grid-template-columns: minmax(0, 1fr); } .route-models { position: static; } .route-model-list { max-height: 250px; } .route-models, .route-editor { padding: 14px; } .route-save-bar { align-items: stretch; } .route-save-bar > .route-inline-actions { margin-left: auto; } .route-entry-actions .icon-button, .route-available > .button { min-width: 40px; min-height: 40px; } }
@media (prefers-reduced-transparency: reduce) { .route-model:hover, .route-model.is-selected, .route-entry { background: var(--panel); } }
</style>
