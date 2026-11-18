import { ApiError } from '../types/api'
import type { ChannelModelTestResult, ChannelModelTestUsage, ModelTestTarget, ProtocolId } from '../types/api'
import type {
  PluginAction, PluginCatalogItem, PluginChoice, PluginContextKind, PluginExecution, PluginField,
  PluginFieldKind, PluginInvocationContext, PluginManifest, PluginRuntimeState, PluginUiContribution,
  PluginUiDescriptor, PluginUiEntry, PluginValue, PluginValues,
} from '../types/plugin'

const MAX_METADATA_BYTES = 256 * 1024
const MAX_PACKAGES = 128
const MAX_TARGETS = 10_000
const MAX_CREDENTIALS = 512
const PLUGIN_ID = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/
const VERSION = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/
const IDENTIFIER = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/
const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
const FORBIDDEN_KEYS = new Set([
  'url', 'endpoint', 'baseurl', 'headers', 'apikey', 'accesstoken', 'refreshtoken', 'secret', 'password',
  '__proto__', 'prototype', 'constructor',
])
const PERMISSIONS = ['diagnostics.channel.probe', 'diagnostics.oauth-account.probe']
const MANIFEST_KEYS = ['id', 'version', 'displayName', 'description', 'schemaVersion', 'apiVersion', 'permissions', 'configuration']
const FIELD_KEYS = ['key', 'label', 'kind', 'required', 'defaultValue', 'help', 'maxLength', 'min', 'max', 'options']
const UI_KEYS = ['schemaVersion', 'entries']

function invalid(label: string): never {
  throw new ApiError(`网关返回的插件${label}格式无效或超出支持范围`, 200, 'invalid_response')
}

function requireContract(condition: unknown, label: string): asserts condition {
  if (!condition) invalid(label)
}

/** Copy plain data properties, rejecting unknown members, accessors and prototype-bearing objects. */
function record(value: unknown, keys?: readonly string[], maximum = 32): Record<string, unknown> {
  requireContract(value !== null && typeof value === 'object' && !Array.isArray(value), '对象')
  const prototype = Object.getPrototypeOf(value)
  requireContract(prototype === Object.prototype || prototype === null, '对象')
  const names = Reflect.ownKeys(value)
  requireContract(names.length <= maximum, '对象')
  const result: Record<string, unknown> = {}
  for (const name of names) {
    requireContract(typeof name === 'string' && !['__proto__', 'prototype', 'constructor'].includes(name), '字段')
    requireContract(keys === undefined || keys.includes(name), '字段')
    const property = Object.getOwnPropertyDescriptor(value, name)
    requireContract(property && 'value' in property && property.enumerable, '字段')
    result[name] = property.value
  }
  return result
}

function array(value: unknown, maximum: number, defaultEmpty = false): unknown[] {
  if (value === undefined && defaultEmpty) return []
  requireContract(Array.isArray(value) && value.length <= maximum, '列表')
  requireContract(Object.getPrototypeOf(value) === Array.prototype, '列表')
  requireContract(Reflect.ownKeys(value).length === value.length + 1, '列表')
  const result: unknown[] = []
  for (let index = 0; index < value.length; index++) {
    const property = Object.getOwnPropertyDescriptor(value, String(index))
    requireContract(property && 'value' in property && property.enumerable, '列表')
    result.push(property.value)
  }
  return result
}

function text(value: unknown, maximum: number, required = false, fallback?: string): string {
  if (value === undefined && fallback !== undefined) return fallback
  requireContract(typeof value === 'string' && value.length <= maximum && !value.includes('\u0000'), '文本')
  requireContract(!required || value.trim().length > 0, '文本')
  // Text is data, including literal angle brackets. Consumers must use text interpolation, never HTML.
  return value
}

function nullableText(value: unknown, maximum: number, required = false): string | null {
  return value == null ? null : text(value, maximum, required)
}

function boolean(value: unknown, fallback?: boolean): boolean {
  if (value === undefined && fallback !== undefined) return fallback
  requireContract(typeof value === 'boolean', '开关')
  return value
}

function integer(value: unknown, minimum: number, maximum: number, fallback?: number): number {
  if (value === undefined && fallback !== undefined) return fallback
  requireContract(typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum, '数值')
  return value
}

function nullableNumber(value: unknown): number | null {
  if (value == null) return null
  requireContract(typeof value === 'number' && Number.isFinite(value), '数值')
  return value
}

function id(value: unknown): string {
  const parsed = text(value, 80, true)
  requireContract(PLUGIN_ID.test(parsed), '标识')
  return parsed
}

function version(value: unknown): string {
  const parsed = text(value, 64, true)
  requireContract(VERSION.test(parsed), '版本')
  return parsed
}

function identifier(value: unknown, fallback?: string): string {
  const parsed = text(value, 64, true, fallback)
  requireContract(IDENTIFIER.test(parsed), '标识')
  return parsed
}

function fieldKey(value: unknown): string {
  const parsed = text(value, 64, true)
  requireContract(FIELD_KEY.test(parsed) && !FORBIDDEN_KEYS.has(parsed.toLowerCase()), '字段')
  return parsed
}

function unique<T>(values: T[], key: (value: T) => string): T[] {
  requireContract(new Set(values.map(key)).size === values.length, '重复项')
  return values
}

function strings(value: unknown, maximumItems: number, maximumLength: number, defaultEmpty = false): string[] {
  return unique(array(value, maximumItems, defaultEmpty).map(item => text(item, maximumLength, true)), item => item)
}

function contextKind(value: unknown): PluginContextKind {
  switch (value) {
    case 'channel': case 'oauth-account': return value
    default: return invalid('上下文')
  }
}

function protocol(value: unknown): ProtocolId {
  switch (value) {
    case 'chat_completions': case 'responses': case 'messages': case 'gemini': return value
    default: return invalid('协议')
  }
}

function fieldKind(value: unknown): PluginFieldKind {
  switch (value) {
    case 'text': case 'textarea': case 'select': case 'boolean': case 'number':
    case 'model': case 'credential': case 'protocol': case 'scope': return value
    default: return invalid('控件')
  }
}

function placement(value: unknown): PluginUiEntry['placement'] {
  switch (value) {
    case 'channel-tests': case 'oauth-account-tests': case 'plugin-page': return value
    default: return invalid('入口')
  }
}

function status(value: unknown): PluginRuntimeState['status'] {
  switch (value) {
    case 'active': case 'pending-restart': case 'disabled': case 'uninstalled': case 'error': return value
    default: return invalid('状态')
  }
}

function scalar(value: unknown): PluginValue {
  if (value === null || typeof value === 'boolean') return value
  if (typeof value === 'string') return text(value, 4_000)
  requireContract(typeof value === 'number' && Number.isFinite(value), '配置值')
  return value
}

function size(value: unknown, maximum = MAX_METADATA_BYTES): void {
  requireContract(new TextEncoder().encode(JSON.stringify(value)).length <= maximum, '数据大小')
}

function values(value: unknown, defaultEmpty = false): PluginValues {
  if (value === undefined && defaultEmpty) return {}
  const data = record(value)
  const parsed: PluginValues = {}
  for (const [key, entry] of Object.entries(data)) parsed[fieldKey(key)] = scalar(entry)
  size(parsed)
  return parsed
}

function choice(value: unknown): PluginChoice {
  const data = record(value, ['value', 'label'])
  return { value: text(data.value, 256, true), label: text(data.label, 200, true) }
}

function validateDefault(field: PluginField): void {
  const value = field.defaultValue
  if (value === null) return // Omitted SDK nullable defaults mean no declared default.
  if (field.kind === 'boolean') requireContract(typeof value === 'boolean', '默认值')
  else if (field.kind === 'number') {
    requireContract(typeof value === 'number' && Number.isFinite(value), '默认值')
    requireContract(field.min === null || value >= field.min, '默认值')
    requireContract(field.max === null || value <= field.max, '默认值')
  } else {
    const maximum = field.maxLength ?? (field.kind === 'text' || field.kind === 'textarea' ? 4_000 : 256)
    requireContract(typeof value === 'string' && value.length <= maximum && (!field.required || value.trim().length > 0), '默认值')
    requireContract(!field.options.length || field.options.some(option => option.value === value), '默认值')
  }
}

function field(value: unknown): PluginField {
  const data = record(value, FIELD_KEYS)
  const parsed: PluginField = {
    key: fieldKey(data.key), label: text(data.label, 200, true), kind: fieldKind(data.kind),
    required: boolean(data.required, false), defaultValue: data.defaultValue == null ? null : scalar(data.defaultValue),
    help: text(data.help, 2_000, false, ''),
    maxLength: data.maxLength == null ? null : integer(data.maxLength, 1, 4_000),
    min: nullableNumber(data.min), max: nullableNumber(data.max),
    options: unique(array(data.options, 128, true).map(choice), item => item.value),
  }
  for (const [kind, key] of [['model', 'model'], ['credential', 'keyId'], ['protocol', 'protocol'], ['scope', 'scope']]) {
    requireContract((parsed.kind !== kind || parsed.key === key) && (parsed.key !== key || parsed.kind === kind), '资源控件')
  }
  requireContract(parsed.maxLength === null || !['boolean', 'number'].includes(parsed.kind), '长度限制')
  requireContract((parsed.min === null && parsed.max === null) || parsed.kind === 'number', '数值限制')
  requireContract(parsed.min === null || parsed.max === null || parsed.min <= parsed.max, '数值限制')
  requireContract(!parsed.options.length || ['select', 'scope', 'model', 'credential', 'protocol'].includes(parsed.kind), '选项')
  requireContract(parsed.kind !== 'select' || parsed.options.length > 0, '选项')
  requireContract(parsed.kind !== 'scope' || (parsed.options.length > 0 && parsed.options.every(option => option.value === 'single' || option.value === 'all')), '范围')
  requireContract(!['prompt', 'defaultPrompt'].includes(parsed.key) || parsed.kind === 'text' || parsed.kind === 'textarea', '提示词控件')
  validateDefault(parsed)
  return parsed
}

function fields(value: unknown, defaultEmpty = false): PluginField[] {
  const parsed = unique(array(value, 32, defaultEmpty).map(field), item => item.key)
  size(parsed)
  return parsed
}

function action(value: unknown): PluginAction {
  const data = record(value, ['id', 'label', 'fields', 'resource', 'supportsBatch', 'resultKind', 'notes'])
  requireContract(data.resource === undefined || data.resource === 'model-test-targets', '资源')
  requireContract(data.resultKind === undefined || data.resultKind === 'probe-v1', '结果类型')
  return {
    id: identifier(data.id), label: text(data.label, 200, true), fields: fields(data.fields),
    resource: 'model-test-targets', supportsBatch: boolean(data.supportsBatch, false), resultKind: 'probe-v1',
    notes: array(data.notes, 16, true).map(item => text(item, 2_000)),
  }
}

function entry(value: unknown): PluginUiEntry {
  const data = record(value, ['id', 'label', 'placement', 'contexts', 'action', 'icon'])
  const contexts = unique(array(data.contexts, 2).map(contextKind), item => item)
  requireContract(contexts.length > 0, '上下文')
  const parsed: PluginUiEntry = {
    id: identifier(data.id), label: text(data.label, 200, true), placement: placement(data.placement),
    contexts, action: action(data.action), icon: identifier(data.icon, 'bolt'),
  }
  requireContract(parsed.placement !== 'channel-tests' || (contexts.length === 1 && contexts[0] === 'channel'), '上下文')
  requireContract(parsed.placement !== 'oauth-account-tests' || (contexts.length === 1 && contexts[0] === 'oauth-account'), '上下文')
  requireContract(!parsed.action.supportsBatch || contexts.includes('channel'), '批量范围')
  if (contexts.includes('oauth-account')) {
    requireContract(!parsed.action.supportsBatch && !parsed.action.fields.some(item => item.kind === 'credential'), '账号控件')
    requireContract(!parsed.action.fields.some(item => item.kind === 'scope' && item.options.some(option => option.value === 'all')), '账号范围')
  }
  return parsed
}

function ui(value: unknown): PluginUiDescriptor {
  const data = record(value, UI_KEYS)
  requireContract(integer(data.schemaVersion, 1, Number.MAX_SAFE_INTEGER, 1) === 1, 'UI 版本')
  const entries = unique(array(data.entries, 32, true).map(entry), item => item.id)
  const schemas = new Map<string, string>()
  for (const item of entries) {
    for (const context of item.contexts) {
      const key = `${context}\u0000${item.action.id}`
      const schema = JSON.stringify(item.action)
      requireContract(!schemas.has(key) || schemas.get(key) === schema, '操作定义')
      schemas.set(key, schema)
    }
  }
  const parsed = { schemaVersion: 1, entries }
  size(parsed)
  return parsed
}

function manifest(value: unknown): PluginManifest {
  const data = record(value, MANIFEST_KEYS)
  const parsed: PluginManifest = {
    id: id(data.id), version: version(data.version), displayName: text(data.displayName, 200, true),
    description: text(data.description, 4_000, false, ''),
    schemaVersion: integer(data.schemaVersion, 1, Number.MAX_SAFE_INTEGER, 1),
    apiVersion: integer(data.apiVersion, 1, Number.MAX_SAFE_INTEGER, 1),
    permissions: strings(data.permissions, 2, 128, true), configuration: fields(data.configuration, true),
  }
  requireContract(parsed.schemaVersion === 1 && parsed.apiVersion === 1, '契约版本')
  requireContract(parsed.permissions.every(permission => PERMISSIONS.includes(permission)), '权限')
  size(parsed)
  return parsed
}

type JsonData = PluginValue | JsonData[] | { [key: string]: JsonData }

/** Unknown-version metadata is bounded inert JSON, never an executable descriptor or a cast. */
function inertData(value: unknown, depth = 0, budget = { keys: 0 }): JsonData {
  requireContract(depth < 32, '数据深度')
  if (value === null || typeof value === 'boolean') return value
  if (typeof value === 'string') return text(value, MAX_METADATA_BYTES)
  if (typeof value === 'number') {
    requireContract(Number.isFinite(value), '数值')
    return value
  }
  if (Array.isArray(value)) return array(value, 4_096).map(item => inertData(item, depth + 1, budget))
  const data = record(value, undefined, 4_096)
  budget.keys += Object.keys(data).length
  requireContract(budget.keys <= 4_096, '字段数量')
  const parsed: { [key: string]: JsonData } = {}
  for (const [key, item] of Object.entries(data)) parsed[key] = inertData(item, depth + 1, budget)
  return parsed
}

export function parsePluginCatalogItem(value: unknown): PluginCatalogItem {
  const data = record(value, ['manifest', 'ui', 'sha256', 'source', 'compatible', 'compatibilityReason'])
  const compatible = boolean(data.compatible, true)
  let parsedManifest: PluginManifest
  let parsedUi: PluginUiDescriptor
  if (compatible) {
    parsedManifest = manifest(data.manifest)
    parsedUi = ui(data.ui)
    for (const item of parsedUi.entries) {
      for (const context of item.contexts) {
        const permission = context === 'channel' ? PERMISSIONS[0] : PERMISSIONS[1]
        requireContract(parsedManifest.permissions.includes(permission), '入口权限')
      }
    }
  } else {
    // Keep future/incompatible packages visible for management, discarding all executable/config UI.
    size(inertData(data.manifest))
    size(inertData(data.ui))
    const display = record(data.manifest, MANIFEST_KEYS)
    const inactiveUi = record(data.ui, UI_KEYS)
    array(display.configuration, 32, true)
    array(inactiveUi.entries, 32, true)
    parsedManifest = {
      // Deployment-reference fallback identities may themselves be invalid; they are display data only.
      id: text(display.id, 128, true), version: text(display.version, 128, true),
      displayName: text(display.displayName, 200, true), description: text(display.description, 4_000, false, ''),
      schemaVersion: integer(display.schemaVersion, 1, Number.MAX_SAFE_INTEGER, 1),
      apiVersion: integer(display.apiVersion, 1, Number.MAX_SAFE_INTEGER, 1),
      permissions: strings(display.permissions, 2, 128, true), configuration: [],
    }
    parsedUi = { schemaVersion: integer(inactiveUi.schemaVersion, 1, Number.MAX_SAFE_INTEGER, 1), entries: [] }
  }
  const digest = text(data.sha256, 64, true)
  requireContract(/^[0-9a-fA-F]{64}$/.test(digest), '摘要')
  const reason = nullableText(data.compatibilityReason, 4_000)
  return {
    manifest: parsedManifest, ui: parsedUi, sha256: digest.toLowerCase(), source: text(data.source, 4_000, true),
    compatible, compatibilityReason: compatible ? reason : reason?.trim() || '该插件与当前宿主不兼容',
  }
}

export function parsePluginCatalog(value: unknown): PluginCatalogItem[] {
  return unique(array(value, MAX_PACKAGES).map(parsePluginCatalogItem), item => `${item.manifest.id}\u0000${item.manifest.version}`)
}

export function parsePluginRuntimeState(value: unknown): PluginRuntimeState {
  const data = record(value, [
    'id', 'displayName', 'description', 'source', 'installed', 'installedVersion', 'activeVersion',
    'desiredEnabled', 'admissionEnabled', 'requiresRestart', 'status', 'loadError', 'permissions',
    'approvedPermissions', 'configuration', 'activeConfiguration', 'configurationFields', 'activeUi',
  ])
  const installed = boolean(data.installed)
  const desiredEnabled = boolean(data.desiredEnabled)
  const admissionEnabled = boolean(data.admissionEnabled)
  const installedVersion = data.installedVersion == null ? null : version(data.installedVersion)
  const activeVersion = data.activeVersion == null ? null : version(data.activeVersion)
  requireContract(!installed || installedVersion !== null, '安装版本')
  requireContract(!admissionEnabled || (installed && desiredEnabled && activeVersion !== null && data.activeUi != null), '执行准入')
  return {
    id: id(data.id), displayName: text(data.displayName, 200, true), description: text(data.description, 4_000),
    source: text(data.source, 4_000, true), installed, installedVersion, activeVersion, desiredEnabled, admissionEnabled,
    requiresRestart: boolean(data.requiresRestart), status: status(data.status), loadError: nullableText(data.loadError, 4_000),
    permissions: strings(data.permissions, 2, 128, true), approvedPermissions: strings(data.approvedPermissions, 2, 128, true),
    configuration: values(data.configuration, true),
    activeConfiguration: data.activeConfiguration == null ? null : values(data.activeConfiguration),
    configurationFields: fields(data.configurationFields, true),
    // Disabled/tombstoned/pending enable metadata never becomes executable, even if a stale UI is present.
    activeUi: admissionEnabled ? ui(data.activeUi) : null,
  }
}

export function parsePlugins(value: unknown): PluginRuntimeState[] {
  return unique(array(value, MAX_PACKAGES).map(parsePluginRuntimeState), item => item.id)
}

export function parsePluginContributions(value: unknown): PluginUiContribution[] {
  return unique(array(value, MAX_PACKAGES * 32).map(item => {
    const data = record(item, ['pluginId', 'activeVersion', 'entry'])
    return { pluginId: id(data.pluginId), activeVersion: version(data.activeVersion), entry: entry(data.entry) }
  }), item => `${item.pluginId}\u0000${item.activeVersion}\u0000${item.entry.id}`)
}

function usage(value: unknown): ChannelModelTestUsage | null {
  if (value == null) return null
  const data = record(value, ['inputTokens', 'outputTokens', 'cachedTokens', 'reasoningTokens', 'totalTokens'])
  const counter = (item: unknown) => item == null ? null : integer(item, 0, Number.MAX_SAFE_INTEGER)
  return {
    inputTokens: counter(data.inputTokens), outputTokens: counter(data.outputTokens), cachedTokens: counter(data.cachedTokens),
    reasoningTokens: counter(data.reasoningTokens), totalTokens: counter(data.totalTokens),
  }
}

export function parsePluginExecution(value: unknown): PluginExecution {
  const data = record(value, ['pluginId', 'activeVersion', 'target', 'plan', 'outcome', 'report'])
  const target = record(data.target, ['kind', 'id', 'model', 'protocol', 'displayName', 'credentialId', 'credentialName'])
  const plan = record(data.plan, ['prompt', 'maxOutputTokens', 'timeoutMs'])
  const outcome = record(data.outcome, ['success', 'statusCode', 'latencyMs', 'responseText', 'errorMessage', 'usage', 'upstreamReportedModel'])
  const report = record(data.report, ['success', 'title', 'metrics', 'notices', 'text'])
  return {
    pluginId: id(data.pluginId), activeVersion: version(data.activeVersion),
    target: {
      kind: contextKind(target.kind), id: text(target.id, 256, true), model: text(target.model, 256, true),
      protocol: protocol(target.protocol), displayName: text(target.displayName, 512, false, ''),
      credentialId: nullableText(target.credentialId, 256, true), credentialName: nullableText(target.credentialName, 512),
    },
    plan: {
      prompt: text(plan.prompt, 4_000, true), maxOutputTokens: integer(plan.maxOutputTokens, 1, 128, 128),
      timeoutMs: integer(plan.timeoutMs, 1, 45_000, 45_000),
    },
    outcome: {
      success: boolean(outcome.success), statusCode: outcome.statusCode == null ? null : integer(outcome.statusCode, 100, 599),
      // Account credential/project preparation can extend latency beyond the 45s probe plan.
      latencyMs: integer(outcome.latencyMs, 0, Number.MAX_SAFE_INTEGER),
      responseText: nullableText(outcome.responseText, 16_000), errorMessage: nullableText(outcome.errorMessage, 4_000),
      usage: usage(outcome.usage), upstreamReportedModel: nullableText(outcome.upstreamReportedModel, 256),
    },
    report: {
      success: boolean(report.success), title: text(report.title, 256, true),
      metrics: array(report.metrics, 32, true).map(item => {
        const metric = record(item, ['label', 'value'])
        return { label: text(metric.label, 128), value: text(metric.value, 512) }
      }),
      notices: array(report.notices, 16, true).map(item => text(item, 2_000)), text: nullableText(report.text, 16_000),
    },
  }
}

export function parsePluginTargets(value: unknown, context?: PluginInvocationContext): ModelTestTarget[] {
  const targets = array(value, MAX_TARGETS).map(item => {
    const data = record(item, ['channelId', 'channelName', 'channelEnabled', 'model', 'protocols', 'credentials'])
    const parsed: ModelTestTarget = {
      channelId: text(data.channelId, 256, true), channelName: text(data.channelName, 512, true),
      channelEnabled: boolean(data.channelEnabled), model: text(data.model, 256, true),
      protocols: unique(array(data.protocols, 4).map(protocol), item => item),
      credentials: unique(array(data.credentials, MAX_CREDENTIALS).map(item => {
        const credential = record(item, ['id', 'name', 'priority'])
        return {
          id: text(credential.id, 256, true), name: text(credential.name, 512, true),
          priority: integer(credential.priority, -2_147_483_648, 2_147_483_647),
        }
      }), item => item.id),
    }
    if (context) {
      contextKind(context.kind)
      requireContract(parsed.channelId === context.id, '目标范围')
      requireContract(context.kind !== 'oauth-account' || parsed.credentials.every(item => item.id === context.id), '账号目标')
    }
    return parsed
  })
  return unique(targets, item => `${item.channelId}\u0000${item.model}`)
}

/** Compatibility UI must use the host probe outcome, never a plugin's independent report verdict/text. */
export function pluginExecutionToModelTestResult(execution: PluginExecution): ChannelModelTestResult {
  return {
    success: execution.outcome.success, model: execution.target.model, protocol: execution.target.protocol,
    keyId: execution.target.credentialId, keyName: execution.target.credentialName,
    statusCode: execution.outcome.statusCode, latencyMs: execution.outcome.latencyMs, prompt: execution.plan.prompt,
    reasoningEffort: null, responseText: execution.outcome.responseText, errorMessage: execution.outcome.errorMessage,
    usage: execution.outcome.usage, upstreamReportedModel: execution.outcome.upstreamReportedModel,
  }
}
