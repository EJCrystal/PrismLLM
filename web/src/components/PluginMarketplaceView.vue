<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { ApiError } from '../types/api'
import type { PluginCatalogItem, PluginRuntimeState, PluginUiContribution, PluginValues } from '../types/api'
import { PLUGIN_STATE_CHANGED, pluginFieldDefaults, pluginFormError, pluginPermissionLabel, pluginStateLabel } from '../utils/pluginForm'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PluginDiagnosticHost from './PluginDiagnosticHost.vue'
import PluginFieldInput from './PluginFieldInput.vue'
import TestEmptyState from './TestEmptyState.vue'

const catalog = ref<PluginCatalogItem[]>([])
const states = ref<PluginRuntimeState[]>([])
const selectedId = ref('')
const selectedVersion = ref('')
const query = ref('')
const loading = ref(true)
const loadError = ref('')
const installationStateKnown = ref(false)
const catalogError = ref('')
const busy = ref(false)
const enableRefreshRequired = reactive(new Set<string>())
const approvedPermissions = ref<string[]>([])
const configuration = reactive<PluginValues>({})
const originalConfiguration = ref<PluginValues>({})
const draftOwnerId = ref('')
const draftInstalledVersion = ref<string | null>(null)
const draftRevision = ref(0)
const draftRejected = ref(false)
const draftFields = ref<PluginRuntimeState['configurationFields']>([])
const diagnosticEntryId = ref('')
const invalidDiagnosticKey = ref('')
const diagnosticGeneration = ref(0)
const diagnosticRechecking = ref(false)
const diagnosticRecheckError = ref('')
const diagnosticRecovery = ref<HTMLElement | null>(null)
let controller: AbortController | null = null
let diagnosticRecheckToken = 0
let disposed = false
let diagnosticTrigger: HTMLButtonElement | null = null

const items = computed(() => [...new Set([...catalog.value.map((item) => item.manifest.id), ...states.value.map((state) => state.id)])].map((id) => {
  const state = states.value.find((item) => item.id === id)
  const available = catalog.value.find((item) => item.manifest.id === id)
  return { id, name: state?.displayName || available?.manifest.displayName || id, state, available }
}).filter((item) => `${item.name} ${item.id}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const selectedState = computed(() => states.value.find((state) => state.id === selectedId.value))
const versions = computed(() => catalog.value.filter((item) => item.manifest.id === selectedId.value))
const selectedCatalog = computed(() => versions.value.find((item) => item.manifest.version === selectedVersion.value))
const selectedName = computed(() => selectedState.value?.displayName || selectedCatalog.value?.manifest.displayName || selectedId.value)
const currentVersionSelected = computed(() => !!selectedState.value?.installed && selectedVersion.value === selectedState.value.installedVersion)
const selectedDescription = computed(() => currentVersionSelected.value
  ? selectedState.value?.description || selectedCatalog.value?.manifest.description || ''
  : selectedCatalog.value?.manifest.description || selectedState.value?.description || '')
const selectedDigest = computed(() => {
  if (currentVersionSelected.value && selectedState.value?.installedSha256) {
    return { label: '已安装包 SHA-256', value: selectedState.value.installedSha256 }
  }
  if (selectedCatalog.value) {
    return { label: currentVersionSelected.value ? '目录同版本包 SHA-256' : '目录校验 SHA-256（待安装）', value: selectedCatalog.value.sha256 }
  }
  return null
})
const catalogDigestConflict = computed(() => currentVersionSelected.value && !!selectedState.value?.installedSha256 && !!selectedCatalog.value &&
  selectedCatalog.value.sha256.toLowerCase() !== selectedState.value.installedSha256.toLowerCase())
const permissions = computed(() => currentVersionSelected.value ? selectedState.value!.permissions : selectedCatalog.value?.manifest.permissions ?? selectedState.value?.permissions ?? [])
const fullGrant = computed(() => permissions.value.every((permission) => approvedPermissions.value.includes(permission)))
const canEnable = computed(() => installationStateKnown.value && !busy.value && !loading.value && !enableRefreshRequired.has(selectedId.value) &&
  currentVersionSelected.value && fullGrant.value)
const dirty = computed(() => Object.keys(configuration).length !== Object.keys(originalConfiguration.value).length ||
  Object.keys(configuration).some((key) => !Object.hasOwn(originalConfiguration.value, key) || !Object.is(configuration[key], originalConfiguration.value[key])))
const draftConflict = computed(() => !!draftOwnerId.value && (
  draftRejected.value || draftOwnerId.value !== selectedId.value ||
  draftInstalledVersion.value !== (selectedState.value?.installedVersion ?? null) ||
  draftRevision.value !== (selectedState.value?.revision ?? 0)
))
const configurationFields = computed(() => draftFields.value)
const configurationError = computed(() => pluginFormError(configurationFields.value, configuration))
const canInstall = computed(() => installationStateKnown.value && !catalogError.value && !loading.value && !!selectedCatalog.value?.compatible && fullGrant.value && !busy.value && (!selectedState.value?.installed || selectedVersion.value !== selectedState.value.installedVersion))
const pageEntries = computed(() => selectedState.value?.admissionEnabled ? selectedState.value.activeUi?.entries.filter((entry) => entry.placement === 'plugin-page') ?? [] : [])
const diagnosticContribution = computed<PluginUiContribution | null>(() => {
  if (diagnosticRechecking.value || invalidDiagnosticKey.value) return null
  const entry = pageEntries.value.find((item) => item.id === diagnosticEntryId.value)
  const state = selectedState.value
  return entry && state?.activeVersion ? { pluginId: state.id, activeVersion: state.activeVersion, entry } : null
})

function errorMessage(cause: unknown) { return cause instanceof Error ? cause.message : String(cause) }

function resetConfiguration() {
  const current = selectedState.value
  draftOwnerId.value = selectedId.value
  draftInstalledVersion.value = current?.installedVersion ?? null
  draftRevision.value = current?.revision ?? 0
  draftRejected.value = false
  draftFields.value = current?.configurationFields.map((field) => ({ ...field, options: field.options.map((option) => ({ ...option })) })) ?? []
  for (const key of Object.keys(configuration)) delete configuration[key]
  Object.assign(configuration, pluginFieldDefaults(draftFields.value, current?.configuration ?? {}, false))
  originalConfiguration.value = { ...configuration }
}

function discardVersionConflict() {
  if (busy.value || loading.value || !draftConflict.value) return
  if (draftRejected.value) { void load(false); return }
  syncVersion()
}

function canLeave(): boolean {
  return !busy.value && (!(dirty.value || draftConflict.value) || window.confirm('插件配置尚未保存，确定放弃这些修改吗？'))
}

defineExpose({ canLeave })

function selectPlugin(id: string) {
  if (id === selectedId.value || !canLeave()) return
  invalidateDiagnosticRecheck()
  selectedId.value = id
  diagnosticEntryId.value = ''
}

function invalidateDiagnosticRecheck() {
  diagnosticRecheckToken++
  diagnosticRechecking.value = false
}

function openDiagnostic(entryId: string, event: MouseEvent) {
  invalidateDiagnosticRecheck()
  diagnosticTrigger = event.currentTarget as HTMLButtonElement
  invalidDiagnosticKey.value = ''
  diagnosticRecheckError.value = ''
  diagnosticGeneration.value++
  diagnosticEntryId.value = entryId
}

function markDiagnosticUnavailable(value: { pluginId: string; activeVersion: string; entryId: string }) {
  const current = diagnosticContribution.value
  if (current?.pluginId !== value.pluginId || current.activeVersion !== value.activeVersion || current.entry.id !== value.entryId) return
  invalidDiagnosticKey.value = `${value.pluginId}:${value.activeVersion}:${value.entryId}`
  diagnosticRecheckError.value = ''
  const invalidKey = invalidDiagnosticKey.value
  void nextTick(() => {
    if (disposed || invalidDiagnosticKey.value !== invalidKey) return
    const recovery = diagnosticRecovery.value
    const action = recovery?.querySelector<HTMLButtonElement>('button:not(:disabled)')
    const target = action ?? recovery
    target?.focus({ preventScroll: true })
  })
}

async function recheckDiagnostic() {
  if (busy.value || diagnosticRechecking.value || !invalidDiagnosticKey.value || !diagnosticEntryId.value) return
  const pluginId = selectedId.value
  const entryId = diagnosticEntryId.value
  const invalidKey = invalidDiagnosticKey.value
  const token = ++diagnosticRecheckToken
  diagnosticRechecking.value = true
  diagnosticRecheckError.value = ''
  try {
    const refreshed = await load()
    if (disposed || token !== diagnosticRecheckToken || selectedId.value !== pluginId ||
      diagnosticEntryId.value !== entryId || invalidDiagnosticKey.value !== invalidKey) return
    if (!refreshed) {
      diagnosticRecheckError.value = loadError.value || '入口状态刷新未完成，请重新检查。'
      return
    }
    const entry = pageEntries.value.find((item) => item.id === entryId && item.contexts.includes('channel'))
    if (!selectedState.value?.activeVersion || !entry) {
      diagnosticRecheckError.value = '刷新后当前生效版本的入口已不可用，弹窗保持关闭。'
      return
    }
    diagnosticGeneration.value++
    invalidDiagnosticKey.value = ''
  } finally {
    if (!disposed && token === diagnosticRecheckToken) diagnosticRechecking.value = false
  }
}

function closeDiagnostic() {
  invalidateDiagnosticRecheck()
  const trigger = diagnosticTrigger
  diagnosticTrigger = null
  diagnosticEntryId.value = ''
  void nextTick(() => {
    if (!disposed && trigger?.isConnected && !trigger.disabled) trigger.focus({ preventScroll: true })
  })
}

function syncVersion() {
  const desired = selectedState.value?.installedVersion
  selectedVersion.value = selectedState.value?.installed && desired ? desired :
    versions.value.find((item) => item.manifest.version === desired)?.manifest.version ?? versions.value[0]?.manifest.version ?? ''
  approvedPermissions.value = [...(selectedState.value?.approvedPermissions ?? [])]
  resetConfiguration()
}

function cancelListRequest() {
  controller?.abort()
  controller = null
  loading.value = false
}

async function load(preserveDraft = true): Promise<boolean> {
  if (busy.value || disposed) return false
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  loadError.value = ''
  const isCurrent = () => !disposed && controller === current && !current.signal.aborted
  let catalogSettled = false
  let catalogFulfilled = false
  let statesFulfilled = false
  // The catalog only gates new installs. Apply it on arrival so a stalled catalog read cannot
  // keep installed-plugin management (including disable/uninstall) in the loading state.
  void api.listPluginCatalog({ signal: current.signal }).then((loaded) => {
    if (!isCurrent()) return
    catalogSettled = true
    catalogFulfilled = true
    catalog.value = loaded
    catalogError.value = ''
    const selectionExists = !selectedId.value || catalog.value.some((item) => item.manifest.id === selectedId.value) ||
      states.value.some((state) => state.id === selectedId.value)
    if (!selectedId.value || (statesFulfilled && !selectionExists)) selectedId.value = items.value[0]?.id ?? ''
    else if (!selectedVersion.value && !(preserveDraft && (dirty.value || draftConflict.value))) syncVersion()
  }, (cause) => {
    if (!isCurrent()) return
    catalogSettled = true
    catalogError.value = errorMessage(cause)
    catalog.value = []
  })
  try {
    const loadedStates = await api.listPlugins({ signal: current.signal })
    if (!isCurrent()) return false
    const hadDraft = preserveDraft && (dirty.value || draftConflict.value)
    states.value = loadedStates
    installationStateKnown.value = true
    if (enableRefreshRequired.has(selectedId.value)) {
      approvedPermissions.value = [...(selectedState.value?.approvedPermissions ?? [])]
    }
    enableRefreshRequired.clear()
    const selectionExists = catalog.value.some((item) => item.manifest.id === selectedId.value) ||
      states.value.some((state) => state.id === selectedId.value)
    if (!selectedId.value || (catalogFulfilled && !selectionExists)) {
      selectedId.value = items.value[0]?.id ?? ''
    }
    if (!hadDraft) syncVersion()
    statesFulfilled = true
    return true
  } catch (cause) {
    if (isCurrent()) {
      installationStateKnown.value = false
      loadError.value = errorMessage(cause)
    }
    return false
  } finally {
    if (isCurrent()) loading.value = false
    // Keep the controller while the catalog read is still in flight so its late result can
    // still be applied; a new load or a mutation aborts and replaces it.
    if (controller === current && catalogSettled) controller = null
  }
}

async function mutate(operation: () => Promise<PluginRuntimeState>, message: string, discardDraft = false, confirmDiscard = discardDraft, configurationSave = false) {
  if (busy.value || !installationStateKnown.value || (confirmDiscard && dirty.value && !canLeave())) return
  const id = selectedId.value
  busy.value = true
  cancelListRequest()
  try {
    const state = await operation()
    if (disposed) return
    states.value = [...states.value.filter((item) => item.id !== state.id), state]
    if (id === selectedId.value && (discardDraft || !dirty.value)) syncVersion()
    notify(message, 'success')
    window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
  } catch (cause) {
    if (!disposed && id === selectedId.value) {
      const conflict = cause instanceof ApiError && cause.status === 409 && cause.code === 'PLUGIN_REVISION_CONFLICT'
      const uncertain = !(cause instanceof ApiError) || cause.status < 400 || cause.status === 408 || cause.status >= 500
      if (conflict && configurationSave) draftRejected.value = true
      if (conflict || uncertain) {
        installationStateKnown.value = false
        loadError.value = uncertain ? '无法确认写入结果，请重新读取安装状态后再操作。' : '安装状态已变化，请重新读取后再操作。'
      }
      notify(errorMessage(cause), 'error')
    }
  } finally {
    if (!disposed) busy.value = false
  }
}

function install() {
  const item = selectedCatalog.value
  if (!canInstall.value || !item) return
  const id = selectedId.value
  const grants = permissions.value.filter((permission) => approvedPermissions.value.includes(permission))
  void mutate(() => api.installPlugin(id, { version: item.manifest.version, expectedSha256: item.sha256, approvedPermissions: grants }), '安装选择已保存；新版本在重启后加载。', true)
}

function toggleEnabled() {
  const state = selectedState.value
  if (!state?.installed || busy.value || !installationStateKnown.value) return
  if (state.desiredEnabled) {
    void mutate(() => api.setPluginEnabled(state.id, { enabled: false }), '已禁用，新的诊断调用已停止准入。')
    return
  }
  const installedVersion = state.installedVersion
  if (!installedVersion || !canEnable.value) return
  const expectedRevision = state.revision
  const grants = [...state.permissions]
  void mutate(async () => {
    try {
      return await api.setPluginEnabled(state.id, {
        enabled: true, installedVersion, expectedRevision, approvedPermissions: grants,
      })
    } catch (cause) {
      if (!disposed && cause instanceof ApiError && cause.status === 409 && cause.code === 'PLUGIN_REVISION_CONFLICT') {
        enableRefreshRequired.add(state.id)
      }
      throw cause
    }
  }, '启用意图已保存，请重启宿主使其生效。')
}

function saveConfiguration() {
  const state = selectedState.value
  if (!state || !draftInstalledVersion.value || draftOwnerId.value !== state.id || draftInstalledVersion.value !== state.installedVersion || draftConflict.value || configurationError.value || busy.value || !dirty.value) return
  const values = { ...configuration }
  const expectedRevision = draftRevision.value
  const installedVersion = draftInstalledVersion.value
  void mutate(() => api.configurePlugin(state.id, { values, expectedRevision, installedVersion }), '插件配置已保存。', true, false, true)
}

function uninstall() {
  const state = selectedState.value
  if (!state?.installed || busy.value || !installationStateKnown.value || !window.confirm(`卸载「${selectedName.value}」并停止新的诊断调用？已保存配置会保留，不会在重启时自动装回。${dirty.value ? '未保存的修改会丢弃。' : ''}`)) return
  void mutate(() => api.uninstallPlugin(state.id), '已卸载并保留配置；重启不会自动装回。', true, false)
}

watch(selectedId, syncVersion)
watch(selectedVersion, () => { approvedPermissions.value = [...(selectedState.value?.approvedPermissions ?? [])] })
onMounted(() => { void load(false) })
onBeforeUnmount(() => { disposed = true; diagnosticTrigger = null; controller?.abort() })
</script>

<template>
  <section class="plugin-marketplace">
    <div class="plugin-market-toolbar">
      <label class="plugin-market-search"><Icon name="search" :size="17" /><input v-model="query" type="search" aria-label="搜索插件" placeholder="搜索插件名称或 ID" /></label>
      <button type="button" class="button ghost" :disabled="loading || busy" @click="load()"><Icon name="refresh" :size="16" />刷新</button>
    </div>
    <p class="callout plugin-market-policy">仅安装受信任目录中的 JVM 插件。安装、升级和启用需人工重启；禁用立即阻止新调用，已发出的请求可能继续执行。</p>
    <div v-if="loadError" class="callout danger" role="alert">{{ loadError }} <button class="link-button" type="button" :disabled="busy" @click="load()">重新读取</button></div>
    <p v-if="catalogError" class="callout danger" role="alert">目录读取失败：{{ catalogError }}。新安装已暂停；仍可管理已安装插件。<button class="link-button" type="button" :disabled="busy" @click="load()">重新读取目录</button></p>
    <p v-if="loading && !catalog.length && !states.length" class="muted plugin-market-loading"><span class="spinner"></span>正在读取插件目录…</p>
    <div v-else class="plugin-market-layout">
      <aside class="panel plugin-market-list" aria-label="插件清单">
        <button v-for="item in items" :key="item.id" type="button" class="plugin-market-item" :class="{ active: selectedId === item.id }"
          :aria-pressed="selectedId === item.id" :disabled="busy" @click="selectPlugin(item.id)">
          <span class="plugin-market-item-name"><Icon name="layers" :size="18" /><strong>{{ item.name }}</strong></span>
          <span class="plugin-market-item-id">{{ item.id }}</span>
          <span class="badge" :class="item.state?.admissionEnabled ? 'success' : item.state?.loadError || item.available?.compatible === false ? 'danger' : 'neutral'">
            {{ item.available?.compatible === false && !item.state?.installed ? '不兼容' : pluginStateLabel(item.state?.status ?? 'uninstalled') }}
          </span>
        </button>
        <p v-if="!items.length" class="muted plugin-market-list-empty">{{ query ? '没有匹配的插件。' : '目录为空。请将受信任的目录与插件包放入部署配置的插件目录后刷新。' }}</p>
      </aside>
      <article v-if="selectedId" class="panel plugin-market-detail">
        <header class="plugin-market-detail-head"><div><h2>{{ selectedName }}</h2><code>{{ selectedId }}</code></div><span v-if="selectedState" class="badge" :class="selectedState.admissionEnabled ? 'success' : selectedState.loadError ? 'danger' : 'neutral'">{{ pluginStateLabel(selectedState.status) }}</span></header>
        <p class="plugin-market-description">{{ selectedDescription }}</p>
        <dl class="plugin-market-facts">
          <div><dt>安装选择</dt><dd>{{ selectedState?.installedVersion || '未安装' }}<span v-if="selectedState && !selectedState.installed && selectedState.installedVersion" class="muted">（已卸载，保留历史选择）</span></dd></div>
          <div><dt>当前生效</dt><dd>{{ selectedState?.admissionEnabled ? selectedState.activeVersion : '无可执行入口' }}<small v-if="selectedState?.activeVersion && !selectedState.admissionEnabled">进程仍载有 {{ selectedState.activeVersion }}，但已关闭准入。</small></dd></div>
          <div><dt>来源</dt><dd>{{ selectedCatalog?.source || selectedState?.source || '可信目录' }}</dd></div>
          <div><dt>期望启用</dt><dd>{{ selectedState?.desiredEnabled ? '启用' : '禁用' }}</dd></div>
        </dl>
        <p v-if="selectedState?.requiresRestart" class="callout warning plugin-market-restart" role="status">安装选择尚未完全生效。请人工重启宿主，再刷新此页核对当前生效版本。</p>
        <p v-if="selectedState?.loadError" class="callout danger" role="alert">{{ selectedState.loadError }}</p>
        <section class="plugin-market-section">
          <h3>版本与权限</h3>
          <label v-if="versions.length" class="field"><span>目录版本</span><select v-model="selectedVersion" :disabled="busy || loading || !installationStateKnown"><option v-if="selectedState?.installed && selectedState.installedVersion && !versions.some((item) => item.manifest.version === selectedState?.installedVersion)" :value="selectedState.installedVersion">{{ selectedState.installedVersion }}（已安装）</option><option v-for="item in versions" :key="item.manifest.version" :value="item.manifest.version">{{ item.manifest.version }}{{ item.compatible ? '' : '（不兼容）' }}</option></select></label>
          <p v-else class="muted small">安装记录仍在，但当前可信目录没有这个插件包。已暂存的版本仍可按原有安装选择加载。</p>
          <p v-if="!installationStateKnown && !loading" class="callout warning" role="status">安装状态尚未读取成功，请重新读取后再选择或安装版本。</p>
          <p v-if="selectedCatalog && !selectedCatalog.compatible" class="callout danger">{{ selectedCatalog.compatibilityReason || '此版本不支持当前宿主。' }}</p>
          <p v-if="selectedDigest" class="plugin-market-digest"><span class="muted small">{{ selectedDigest.label }}</span><code>{{ selectedDigest.value }}</code></p>
          <p v-if="catalogDigestConflict" class="callout warning plugin-market-digest-conflict" role="status">目录中同版本包的摘要与已安装包不同。已安装版本按不可变记录固定，不能替换；如需更换包内容，请发布更高版本后重新审阅并安装。</p>
          <fieldset class="plugin-market-permissions" :disabled="busy"><legend>明确授权下列诊断能力</legend><label v-for="permission in permissions" :key="permission"><input v-model="approvedPermissions" type="checkbox" :value="permission" /><span>{{ pluginPermissionLabel(permission) }}</span></label><p v-if="!permissions.length" class="muted small">此版本未请求诊断能力。</p></fieldset>
          <small class="muted">JVM 插件是可信同进程代码，不是恶意代码沙箱；目录摘要校验不等同于发布者签名。</small>
          <div class="plugin-market-actions">
            <button v-if="selectedCatalog" type="button" class="button primary" :disabled="!canInstall" @click="install">{{ !selectedState?.installed ? '安装此版本' : selectedVersion === selectedState.installedVersion ? '已安装此版本' : '选择此版本' }}</button>
            <button v-if="selectedState?.installed" type="button" class="button" :disabled="selectedState.desiredEnabled ? busy || !installationStateKnown : !canEnable" @click="toggleEnabled">{{ selectedState.desiredEnabled ? '禁用' : '启用（重启生效）' }}</button>
            <button v-if="selectedState?.installed" type="button" class="button ghost danger-text" :disabled="busy || !installationStateKnown" @click="uninstall">卸载</button>
          </div>
        </section>
        <section v-if="configurationFields.length || draftConflict" class="plugin-market-section">
          <h3>插件配置<span class="muted small"> · 草稿归属 {{ draftOwnerId || selectedId }} · 安装选择 {{ draftInstalledVersion ?? '无' }}</span></h3>
          <p v-if="draftConflict" class="callout warning" role="alert">当前配置草稿属于 {{ draftOwnerId }} 安装版本 {{ draftInstalledVersion ?? '无' }}、revision {{ draftRevision }}；当前安装选择为 {{ selectedId }} {{ selectedState?.installedVersion ?? '无' }}、revision {{ selectedState?.revision ?? 0 }}。配置或安装选择已变化，请放弃旧草稿后载入当前配置，不能将旧版本或旧 revision 草稿保存到当前状态。</p>
          <p v-if="selectedState?.activeVersion && selectedState.activeVersion !== selectedState.installedVersion" class="muted small">这里保存待生效版本的配置；当前运行的旧版本继续使用自己的配置快照。</p>
          <form class="plugin-market-config" @submit.prevent="saveConfiguration"><PluginFieldInput v-for="field in configurationFields" :key="field.key" :field="field" v-model="configuration[field.key]" :disabled="busy || draftConflict" />
            <small v-if="configurationError" class="danger-text" role="status">{{ configurationError }}</small><div class="plugin-market-actions"><button type="submit" class="button primary" :disabled="!installationStateKnown || busy || draftConflict || !dirty || !!configurationError">保存配置</button><button v-if="draftConflict" type="button" class="button ghost" :disabled="busy" @click="discardVersionConflict">放弃旧草稿并载入当前配置</button><button v-else type="button" class="button ghost" :disabled="busy || !dirty" @click="resetConfiguration">撤销修改</button></div></form>
        </section>
        <section v-if="pageEntries.length" class="plugin-market-section"><h3>运行诊断<span class="muted small"> · 当前生效 {{ selectedState?.activeVersion }}</span></h3>
          <div class="plugin-market-actions"><template v-for="entry in pageEntries" :key="entry.id"><button v-if="entry.contexts.includes('channel')" type="button" class="button" :disabled="busy" @click="openDiagnostic(entry.id, $event)"><Icon :name="entry.icon" :size="16" />{{ entry.label }}</button><small v-else class="muted">{{ entry.label }}请从订阅账号页面打开。</small></template></div>
        </section>
      </article>
      <TestEmptyState v-else icon="layers" title="选择一个插件" description="从清单查看版本、能力授权和配置。安装不代表当前进程已经加载。" />
    </div>
    <Modal v-if="diagnosticContribution" :key="diagnosticGeneration" :title="diagnosticContribution.entry.label" full :body-scrollable="false" @close="closeDiagnostic"><PluginDiagnosticHost :contribution="diagnosticContribution" @unavailable="markDiagnosticUnavailable" /></Modal>
    <p v-else-if="diagnosticEntryId && (invalidDiagnosticKey || diagnosticRecheckError)" ref="diagnosticRecovery" class="callout danger" role="alert" tabindex="-1">{{ diagnosticRecheckError || '此生效版本的诊断入口已不可用。' }}<button v-if="invalidDiagnosticKey" type="button" class="button ghost" :disabled="busy || diagnosticRechecking" @click="recheckDiagnostic">{{ diagnosticRechecking ? '正在检查…' : '重新检查' }}</button>诊断弹窗已关闭，请重新检查入口后重试。</p>
  </section>
</template>

<style scoped>
.plugin-marketplace { display: flex; flex: 1 1 auto; flex-direction: column; gap: 16px; min-width: 0; min-height: 0; }
.plugin-market-toolbar { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.plugin-market-search { display: flex; flex: 1 1 220px; align-items: center; gap: 8px; max-width: 480px; min-width: 0; }
.plugin-market-search input { flex: 1 1 auto; width: 100%; min-width: 0; min-height: 44px; padding: 10px 12px; border: 1px solid var(--glass-line); border-radius: 10px; background: var(--glass-field); color: var(--ink); font: inherit; }
.plugin-market-policy { flex: 0 0 auto; margin: 0; font-size: 12px; line-height: 1.7; }
.plugin-market-layout { display: grid; flex: 1 1 auto; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); align-items: start; gap: 18px; min-width: 0; min-height: 0; overflow: auto; overscroll-behavior: contain; }
.plugin-market-list { display: grid; gap: 4px; padding: 8px; min-width: 0; }
.plugin-market-item { display: grid; gap: 8px; justify-items: start; min-width: 0; padding: 14px 12px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
.plugin-market-item.active { border-color: color-mix(in srgb, var(--brand) 30%, var(--glass-line)); background: color-mix(in srgb, var(--brand) 8%, var(--glass-field)); }
.plugin-market-item:hover { background: var(--glass-field); }
.plugin-market-item-name { display: flex; align-items: center; gap: 8px; min-width: 0; font-size: 14px; overflow-wrap: anywhere; }
.plugin-market-item-name svg { flex-shrink: 0; }
.plugin-market-item-id { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.plugin-market-detail { display: grid; gap: 18px; min-width: 0; padding: 24px; }
.plugin-market-detail-head { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.plugin-market-detail-head h2 { margin: 0 0 6px; font-size: 20px; overflow-wrap: anywhere; }
.plugin-market-detail-head code { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.plugin-market-description { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.plugin-market-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 0; }
.plugin-market-facts div, .plugin-market-facts dd { display: grid; gap: 4px; min-width: 0; }
.plugin-market-facts dt { color: var(--muted); font-size: 12px; }
.plugin-market-facts dd { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.plugin-market-facts small { color: var(--muted); font-size: 11px; line-height: 1.6; }
.plugin-market-section, .plugin-market-config { display: grid; gap: 14px; min-width: 0; }
.plugin-market-section { padding-top: 20px; border-top: 1px solid var(--glass-line); }
.plugin-market-section h3 { margin: 0; font-size: 14px; }
.plugin-market-digest { display: grid; gap: 5px; margin: 0; }
.plugin-market-digest code { font-size: 11px; overflow-wrap: anywhere; }
.plugin-market-permissions { display: grid; gap: 6px; margin: 0; padding: 0; border: 0; }
.plugin-market-permissions legend { margin-bottom: 6px; font-size: 12px; font-weight: 600; }
.plugin-market-permissions label { display: flex; align-items: center; gap: 8px; min-height: 36px; font-size: 12px; line-height: 1.6; }
.plugin-market-permissions input { flex: 0 0 auto; width: 17px; height: 17px; margin: 0; accent-color: var(--brand); }
.plugin-market-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.plugin-market-list-empty { padding: 8px; font-size: 12px; line-height: 1.7; }
.plugin-market-loading { display: flex; align-items: center; gap: 10px; }
.plugin-market-item:focus-visible, .plugin-market-search input:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 900px) {
  .plugin-market-layout { grid-template-columns: 1fr; }
  .plugin-market-list { grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
  .plugin-market-detail { padding: 18px; }
  .plugin-market-actions .button, .plugin-market-permissions label { min-height: 44px; }
}
@media (max-width: 420px) { .plugin-market-facts { grid-template-columns: 1fr; } }
</style>
