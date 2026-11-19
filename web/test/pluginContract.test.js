import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'
import { ApiError } from '../src/types/api'
import {
  parsePluginCatalog, parsePluginCatalogItem, parsePluginContributions, parsePluginExecution,
  parsePluginRuntimeState, parsePluginTargets, parsePlugins, pluginExecutionToModelTestResult,
} from '../src/utils/plugin'

const SECOND_PLUGIN = 'example.diagnostic-two'
const CHANNEL_PERMISSION = 'diagnostics.channel.probe'
const ACCOUNT_PERMISSION = 'diagnostics.oauth-account.probe'
const DEFAULT_PROMPT = '请只回复 OK'

function channelFields() {
  return [
    { key: 'model', label: '模型', kind: 'model', required: true },
    { key: 'keyId', label: '凭据', kind: 'credential', required: true },
    { key: 'protocol', label: '协议', kind: 'protocol', required: true },
    { key: 'scope', label: '范围', kind: 'scope', defaultValue: 'single', options: [
      { value: 'single', label: '当前模型' }, { value: 'all', label: '全部模型' },
    ] },
    { key: 'prompt', label: '提示词', kind: 'textarea', maxLength: 4000 },
  ]
}

function catalog(pluginId = 'prism.connectivity') {
  const entry = (id, placement, context, actionId, fields, supportsBatch) => ({
    id, label: '连接诊断', placement, contexts: [context],
    action: { id: actionId, label: '执行诊断', fields, supportsBatch },
  })
  return {
    manifest: {
      id: pluginId, version: '1.0.0', displayName: '连接诊断',
      permissions: [CHANNEL_PERMISSION, ACCOUNT_PERMISSION],
      configuration: [{
        key: 'defaultPrompt', label: '默认提示词', kind: 'textarea', required: true,
        defaultValue: DEFAULT_PROMPT, maxLength: 4000,
      }],
    },
    ui: { entries: [
      entry('channel-entry', 'channel-tests', 'channel', 'probe-channel', channelFields(), true),
      entry('account-entry', 'oauth-account-tests', 'oauth-account', 'probe-account',
        channelFields().filter(field => !['keyId', 'scope'].includes(field.key)), false),
      entry('page-entry', 'plugin-page', 'channel', 'probe-global', channelFields(), true),
    ] },
    sha256: 'A'.repeat(64), source: `${pluginId}.prismplugin`,
  }
}

function runtime(overrides = {}) {
  return {
    id: SECOND_PLUGIN, displayName: '另一个诊断插件', description: '', source: 'second.prismplugin',
    installed: false, desiredEnabled: false, admissionEnabled: false,
    requiresRestart: false, status: 'uninstalled', ...overrides,
  }
}

function execution() {
  return {
    pluginId: SECOND_PLUGIN, activeVersion: '1.0.0',
    target: { kind: 'channel', id: 'channel/one', model: 'vendor/model', protocol: 'responses' },
    plan: { prompt: DEFAULT_PROMPT },
    outcome: { success: false, latencyMs: 0 },
    report: { success: true, title: '独立报告 <结果>' },
  }
}

function target(overrides = {}) {
  return {
    channelId: 'channel/one', channelName: '渠道一', channelEnabled: true,
    model: 'vendor/model', protocols: ['responses', 'gemini'],
    credentials: [{ id: 'key/one', name: '凭据显示名', priority: 0 }], ...overrides,
  }
}

function invalidResponse(error) {
  return error instanceof ApiError && error.status === 200 && error.code === 'invalid_response'
}

function replaceGlobals(t, overrides) {
  const originals = new Map(Object.keys(overrides).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  t.after(() => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  })
  for (const [key, value] of Object.entries(overrides)) {
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true })
  }
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status })
}

function invocation(context = { kind: 'channel', id: 'channel/one' }) {
  return {
    actionId: 'probe-channel', context,
    values: { model: 'vendor/model', keyId: 'key/one', protocol: 'responses', scope: 'single', prompt: 'run prompt' },
    expectedActiveVersion: '1.0.0',
  }
}

describe('bounded declarative plugin contracts', () => {
  it('normalizes official-like SDK defaults and nullable fields without plugin-ID dispatch', () => {
    const parsed = parsePluginCatalog([catalog(), catalog(SECOND_PLUGIN)])
    assert.deepEqual(parsed.map(item => item.manifest.id), ['prism.connectivity', SECOND_PLUGIN])
    for (const item of parsed) {
      assert.equal(item.compatible, true)
      assert.equal(item.compatibilityReason, null)
      assert.equal(item.sha256, 'a'.repeat(64))
      assert.equal(item.manifest.schemaVersion, 1)
      assert.equal(item.manifest.apiVersion, 1)
      assert.equal(item.manifest.description, '')
      assert.equal(item.ui.schemaVersion, 1)
      const configured = item.manifest.configuration[0]
      assert.deepEqual(configured, {
        key: 'defaultPrompt', label: '默认提示词', kind: 'textarea', required: true,
        defaultValue: DEFAULT_PROMPT, maxLength: 4000, help: '', min: null, max: null, options: [],
      })
      const entry = item.ui.entries[0]
      assert.equal(entry.icon, 'bolt')
      assert.equal(entry.action.resource, 'model-test-targets')
      assert.equal(entry.action.resultKind, 'probe-v1')
      assert.deepEqual(entry.action.notes, [])
      assert.deepEqual(entry.action.fields[4], {
        key: 'prompt', label: '提示词', kind: 'textarea', required: false,
        defaultValue: null, maxLength: 4000, help: '', min: null, max: null, options: [],
      })
      assert.deepEqual(item.ui.entries[1].action.fields.map(field => field.key), ['model', 'protocol', 'prompt'])
    }
  })

  it('supports every finite scalar control and preserves declared configuration defaults', () => {
    const raw = catalog(SECOND_PLUGIN)
    raw.manifest.configuration.push(
      { key: 'label', label: '文本', kind: 'text', defaultValue: '<literal text>' },
      { key: 'mode', label: '模式', kind: 'select', options: [{ value: 'brief', label: '简短' }], defaultValue: 'brief' },
      { key: 'showDetails', label: '详情', kind: 'boolean', defaultValue: false },
      { key: 'threshold', label: '阈值', kind: 'number', min: 0.5, max: 2, defaultValue: 1.5 },
    )
    const fields = parsePluginCatalogItem(raw).manifest.configuration
    assert.deepEqual(fields.map(field => field.defaultValue), [DEFAULT_PROMPT, '<literal text>', 'brief', false, 1.5])
    assert.equal(fields[4].min, 0.5)
    assert.equal(fields[4].max, 2)
  })

  it('keeps future and invalid-header catalogue rows visible but discards all executable/config UI', () => {
    const future = catalog(SECOND_PLUGIN)
    future.compatible = false
    future.compatibilityReason = 'Unsupported API version 2'
    future.manifest.schemaVersion = 2
    future.manifest.apiVersion = 2
    future.manifest.configuration = [{ kind: 'future-widget', expression: 'uninterpreted data' }]
    future.ui = { schemaVersion: 2, entries: [{ kind: 'html', script: 'uninterpreted data' }] }
    const invalidHeader = catalog('x'.repeat(100))
    invalidHeader.compatible = false
    invalidHeader.compatibilityReason = 'Invalid plugin identity/version'
    invalidHeader.manifest.version = 'not-semver-' + 'x'.repeat(70)
    const items = parsePluginCatalog([catalog(), future, invalidHeader])
    assert.equal(items.length, 3)
    assert.equal(items[1].manifest.apiVersion, 2)
    assert.equal(items[1].ui.schemaVersion, 2)
    assert.equal(items[1].compatibilityReason, future.compatibilityReason)
    assert.equal(items[2].manifest.id, invalidHeader.manifest.id)
    assert.equal(items[2].manifest.version, invalidHeader.manifest.version)
    for (const item of items.slice(1)) {
      assert.equal(item.compatible, false)
      assert.deepEqual(item.manifest.configuration, [])
      assert.deepEqual(item.ui.entries, [])
    }
    delete future.compatibilityReason
    assert.equal(parsePluginCatalogItem(future).compatibilityReason, '该插件与当前宿主不兼容')
  })

  it('enforces plugin ID80/version64 independently of entry/action/field ID64', () => {
    const raw = catalog('x'.repeat(80))
    raw.manifest.version = '1.0.0-' + 'x'.repeat(58)
    raw.ui.entries[0].id = 'x'.repeat(64)
    raw.ui.entries[0].action.id = 'x'.repeat(64)
    raw.manifest.configuration[0].key = 'x'.repeat(64)
    const parsed = parsePluginCatalogItem(raw)
    assert.equal(parsed.manifest.id.length, 80)
    assert.equal(parsed.manifest.version.length, 64)
    for (const mutate of [
      data => { data.manifest.id += 'x' },
      data => { data.manifest.version += 'x' },
      data => { data.ui.entries[0].id += 'x' },
      data => { data.ui.entries[0].action.id += 'x' },
      data => { data.manifest.configuration[0].key += 'x' },
    ]) {
      const invalid = structuredClone(raw)
      mutate(invalid)
      assert.throws(() => parsePluginCatalogItem(invalid), invalidResponse)
    }
  })

  const malformedDescriptors = [
    ['manifest schema2 marked executable', data => { data.manifest.schemaVersion = 2 }],
    ['API2 marked executable', data => { data.manifest.apiVersion = 2 }],
    ['UI schema2 marked executable', data => { data.ui.schemaVersion = 2 }],
    ['missing identity', data => { delete data.manifest.id }],
    ['invalid identity grammar', data => { data.manifest.id = 'bad/id' }],
    ['invalid semver', data => { data.manifest.version = 'latest' }],
    ['unknown permission', data => { data.manifest.permissions = ['network.anywhere'] }],
    ['missing context permission', data => { data.manifest.permissions = [CHANNEL_PERMISSION] }],
    ['duplicate permissions', data => { data.manifest.permissions = [CHANNEL_PERMISSION, CHANNEL_PERMISSION] }],
    ['nonboolean compatibility', data => { data.compatible = 'true' }],
    ['invalid digest', data => { data.sha256 = 'g'.repeat(64) }],
    ['unknown manifest script', data => { data.manifest.script = 'uninterpreted data' }],
    ['unknown UI HTML hook', data => { data.ui.html = '<div>uninterpreted</div>' }],
    ['unknown entry callback', data => { data.ui.entries[0].onClick = 'uninterpreted data' }],
    ['arbitrary action endpoint', data => { data.ui.entries[0].action.endpoint = 'https://untrusted.example.test/' }],
    ['unknown action resource', data => { data.ui.entries[0].action.resource = 'custom-targets' }],
    ['unknown result kind', data => { data.ui.entries[0].action.resultKind = 'html' }],
    ['unknown placement', data => { data.ui.entries[0].placement = 'custom-page' }],
    ['wrong placement context', data => { data.ui.entries[0].contexts = ['oauth-account'] }],
    ['empty contexts', data => { data.ui.entries[0].contexts = [] }],
    ['unknown context', data => { data.ui.entries[0].contexts = ['other'] }],
    ['duplicate contexts', data => { data.ui.entries[0].contexts = ['channel', 'channel'] }],
    ['unsafe icon', data => { data.ui.entries[0].icon = '<svg>' }],
    ['HTML control', data => { data.manifest.configuration[0].kind = 'html' }],
    ['expression hook', data => { data.manifest.configuration[0].expression = 'uninterpreted data' }],
    ['nested default value', data => { data.manifest.configuration[0].defaultValue = { prompt: 'not scalar' } }],
    ['array default value', data => { data.manifest.configuration[0].defaultValue = [] }],
    ['unsafe field key', data => { data.manifest.configuration[0].key = 'accessToken' }],
    ['prototype field key', data => { data.manifest.configuration[0].key = '__proto__' }],
    ['misbound model field', data => { data.ui.entries[0].action.fields[0].key = 'chosenModel' }],
    ['misbound credential field', data => { data.ui.entries[0].action.fields[1].key = 'credentialId' }],
    ['misbound protocol field', data => { data.ui.entries[0].action.fields[2].key = 'wireProtocol' }],
    ['misbound scope field', data => { data.ui.entries[0].action.fields[3].key = 'batch' }],
    ['canonical model key on text control', data => { data.ui.entries[0].action.fields[0].kind = 'text' }],
    ['nontext prompt control', data => { data.ui.entries[0].action.fields[4].kind = 'boolean' }],
    ['nontext configured prompt', data => { data.manifest.configuration[0].kind = 'number' }],
    ['overlong configured default', data => { data.manifest.configuration[0].defaultValue = 'x'.repeat(4001) }],
    ['blank required default', data => { data.manifest.configuration[0].defaultValue = ' ' }],
    ['NUL default', data => { data.manifest.configuration[0].defaultValue = 'a\u0000b' }],
    ['invalid choice default', data => { data.ui.entries[0].action.fields[3].defaultValue = 'other' }],
    ['unknown batch scope', data => { data.ui.entries[0].action.fields[3].options[0].value = 'network' }],
    ['duplicate choices', data => { data.ui.entries[0].action.fields[3].options.push({ value: 'single', label: '重复' }) }],
    ['select without choices', data => { data.manifest.configuration = [{ key: 'mode', label: '模式', kind: 'select' }] }],
    ['boolean length limit', data => { data.manifest.configuration = [{ key: 'flag', label: '开关', kind: 'boolean', maxLength: 1 }] }],
    ['text number limit', data => { data.manifest.configuration[0].min = 1 }],
    ['reversed number bounds', data => { data.manifest.configuration = [{ key: 'n', label: '数值', kind: 'number', min: 2, max: 1 }] }],
    ['nonfinite default', data => { data.manifest.configuration = [{ key: 'n', label: '数值', kind: 'number', defaultValue: Infinity }] }],
    ['wrong boolean default', data => { data.manifest.configuration = [{ key: 'flag', label: '开关', kind: 'boolean', defaultValue: 'false' }] }],
    ['out-of-range number default', data => { data.manifest.configuration = [{ key: 'n', label: '数值', kind: 'number', min: 2, defaultValue: 1 }] }],
    ['account credential selector', data => { data.ui.entries[1].action.fields.push(channelFields()[1]) }],
    ['account batch mode', data => { data.ui.entries[1].action.supportsBatch = true }],
    ['batch scope without single', data => { data.ui.entries[0].action.fields[3].options = [{ value: 'all', label: '全部模型' }]; data.ui.entries[0].action.fields[3].defaultValue = 'all' }],
    ['nonbatch required scope without single', data => { data.ui.entries[0].action.supportsBatch = false; data.ui.entries[0].action.fields[3].required = true; data.ui.entries[0].action.fields[3].options = [{ value: 'all', label: '全部模型' }]; data.ui.entries[0].action.fields[3].defaultValue = 'all' }],
    ['account all-only scope', data => {
      const scope = channelFields()[3]
      scope.required = true
      scope.defaultValue = 'all'
      scope.options = [{ value: 'all', label: '全部模型' }]
      data.ui.entries[1].action.fields.push(scope)
    }],
    ['duplicate UI entry', data => { data.ui.entries.push(structuredClone(data.ui.entries[0])) }],
    ['duplicate fields', data => { data.manifest.configuration.push(structuredClone(data.manifest.configuration[0])) }],
    ['ambiguous shared action', data => { data.ui.entries[2].action.id = 'probe-channel'; data.ui.entries[2].action.label = '不同定义' }],
    ['too many fields', data => { data.manifest.configuration = Array.from({ length: 33 }, (_, index) => ({ key: `f${index}`, label: '字段', kind: 'text' })) }],
    ['too many choices', data => { data.manifest.configuration = [{ key: 'mode', label: '模式', kind: 'select', options: Array.from({ length: 129 }, (_, index) => ({ value: String(index), label: '选项' })) }] }],
    ['too many UI entries', data => { data.ui.entries = Array.from({ length: 33 }, (_, index) => ({ ...structuredClone(data.ui.entries[0]), id: `entry${index}` })) }],
    ['too many action notes', data => { data.ui.entries[0].action.notes = Array(17).fill('note') }],
    ['overlong action note', data => { data.ui.entries[0].action.notes = ['x'.repeat(2001)] }],
  ]
  for (const [name, mutate] of malformedDescriptors) {
    it(`rejects ${name}`, () => {
      const raw = catalog(SECOND_PLUGIN)
      mutate(raw)
      assert.throws(() => parsePluginCatalogItem(raw), invalidResponse)
    })
  }

  for (const kind of ['model', 'protocol']) {
    for (const requirement of ['missing', 'optional', 'omitted-required']) {
      it(`rejects ${requirement} ${kind} resource fields in every executable descriptor parser`, () => {
        for (let index = 0; index < catalog().ui.entries.length; index++) {
          const raw = catalog(SECOND_PLUGIN)
          const action = raw.ui.entries[index].action
          if (requirement === 'missing') action.fields = action.fields.filter(field => field.kind !== kind)
          else {
            const field = action.fields.find(field => field.kind === kind)
            if (requirement === 'optional') field.required = false
            else delete field.required
          }
          const label = `${raw.ui.entries[index].placement}: ${requirement} ${kind}`
          assert.throws(() => parsePluginCatalogItem(raw), invalidResponse, `catalogue ${label}`)
          assert.throws(() => parsePluginRuntimeState(runtime({
            installed: true, installedVersion: '1.0.0', activeVersion: '1.0.0',
            desiredEnabled: true, admissionEnabled: true, status: 'active', activeUi: raw.ui,
          })), invalidResponse, `runtime ${label}`)
          assert.throws(() => parsePluginContributions([{
            pluginId: SECOND_PLUGIN, activeVersion: '1.0.0', entry: raw.ui.entries[index],
          }]), invalidResponse, `contribution ${label}`)
        }
      })
    }
  }

  it('validates scope choices after field length constraints and checks defaults in every context', () => {
    const makeScopeEntry = () => {
      const raw = catalog(SECOND_PLUGIN)
      const item = raw.ui.entries[0]
      item.placement = 'plugin-page'
      item.contexts = ['channel', 'oauth-account']
      item.action.id = 'shared-probe'
      item.action.supportsBatch = true
      item.action.fields = item.action.fields.filter(field => field.key !== 'keyId')
      const scope = item.action.fields.find(field => field.kind === 'scope')
      scope.maxLength = 6
      return { raw, item, scope }
    }

    const { raw, item, scope } = makeScopeEntry()
    scope.defaultValue = 'all'
    assert.throws(() => parsePluginCatalogItem(raw), invalidResponse,
      'batch all remains an available explicit channel choice but cannot default in a shared OAuth context')

    scope.defaultValue = 'single'
    assert.equal(parsePluginCatalogItem(raw).ui.entries[0].action.fields.find(field => field.kind === 'scope').defaultValue, 'single')
    scope.defaultValue = null
    assert.equal(parsePluginCatalogItem(raw).ui.entries[0].action.fields.find(field => field.kind === 'scope').options.some(option => option.value === 'all'), true,
      'batch channel invocations retain all as an explicit choice')

    const lengthBound = catalog(SECOND_PLUGIN)
    const channelEntry = lengthBound.ui.entries[0]
    channelEntry.action.supportsBatch = false
    const lengthBoundScope = channelEntry.action.fields.find(field => field.kind === 'scope')
    lengthBoundScope.required = true
    lengthBoundScope.defaultValue = null
    lengthBoundScope.options = [{ value: 'single', label: '当前目标' }]
    lengthBoundScope.maxLength = 5
    assert.throws(() => parsePluginCatalogItem(lengthBound), invalidResponse,
      'single is not a usable required option when its value exceeds maxLength')

    lengthBoundScope.maxLength = 6
    assert.equal(parsePluginCatalogItem(lengthBound).ui.entries[0].action.fields.find(field => field.kind === 'scope').options[0].value, 'single')
  })

  it('validates static choices against their own field limits without requiring defaults', () => {
    const choices = [
      ['mode', 'select', 'enabled'],
      ['model', 'model', 'vendor/model'],
      ['keyId', 'credential', 'opaque/key'],
      ['protocol', 'protocol', 'custom.protocol'],
      ['scope', 'scope', 'single'],
    ]
    for (const [key, kind, value] of choices) {
      for (const required of ['model', 'protocol'].includes(kind) ? [true] : [false, true]) {
        const field = {
          key, label: 'Choice', kind, required, maxLength: value.length,
          options: [{ value, label: 'Choice' }],
        }
        const raw = catalog(SECOND_PLUGIN)
        raw.manifest.configuration = [field]
        raw.ui.entries[0].action.fields = raw.ui.entries[0].action.fields.filter(item => item.key !== key).concat(field)
        const parsed = parsePluginCatalogItem(raw)
        assert.equal(parsed.manifest.configuration[0].defaultValue, null)
        assert.equal(parsed.ui.entries[0].action.fields.find(item => item.key === key).defaultValue, null)
        assert.deepEqual(parsed.manifest.configuration[0].options, field.options)
        assert.deepEqual(parsed.ui.entries[0].action.fields.find(item => item.key === key).options, field.options)

        for (const maximum of [1, value.length - 1]) {
          const invalid = { ...field, maxLength: maximum }
          const configuration = catalog(SECOND_PLUGIN)
          configuration.manifest.configuration = [invalid]
          assert.throws(() => parsePluginCatalogItem(configuration), invalidResponse, `${kind} configuration maxLength=${maximum}`)
          const action = catalog(SECOND_PLUGIN)
          action.ui.entries[0].action.fields = action.ui.entries[0].action.fields.filter(item => item.key !== key).concat(invalid)
          assert.throws(() => parsePluginCatalogItem(action), invalidResponse, `${kind} action maxLength=${maximum}`)
        }
        if (kind === 'select') {
          const mixed = { ...field, options: [...field.options, { value: value + 'x', label: 'Too long' }] }
          const configuration = catalog(SECOND_PLUGIN)
          configuration.manifest.configuration = [mixed]
          assert.throws(() => parsePluginCatalogItem(configuration), invalidResponse)
          const action = catalog(SECOND_PLUGIN)
          action.ui.entries[0].action.fields.push(mixed)
          assert.throws(() => parsePluginCatalogItem(action), invalidResponse)
        }
      }
    }
  })

  it('permits identical action reuse across placements for the same context', () => {
    const raw = catalog(SECOND_PLUGIN)
    raw.ui.entries[2].action = structuredClone(raw.ui.entries[0].action)
    assert.equal(parsePluginCatalogItem(raw).ui.entries[2].action.id, 'probe-channel')
  })

  it('rejects non-data objects/accessors/arrays without executing getters or mutating prototypes', () => {
    let getterCalls = 0
    const accessor = catalog()
    Object.defineProperty(accessor.manifest, 'displayName', {
      enumerable: true, get() { getterCalls++; return 'never read' },
    })
    assert.throws(() => parsePluginCatalogItem(accessor), invalidResponse)
    assert.equal(getterCalls, 0)
    const prototype = catalog()
    Object.setPrototypeOf(prototype.manifest, { inherited: true })
    assert.throws(() => parsePluginCatalogItem(prototype), invalidResponse)
    const polluted = catalog()
    polluted.manifest = JSON.parse('{"__proto__":{"polluted":true}}')
    assert.throws(() => parsePluginCatalogItem(polluted), invalidResponse)
    assert.equal(Object.prototype.polluted, undefined)
    const symbol = catalog()
    symbol[Symbol('extension')] = true
    assert.throws(() => parsePluginCatalogItem(symbol), invalidResponse)
    const sparse = new Array(1)
    assert.throws(() => parsePluginCatalog(sparse), invalidResponse)
    const extended = [catalog()]
    extended.extra = true
    assert.throws(() => parsePluginCatalog(extended), invalidResponse)
  })

  it('bounds aggregate field metadata by UTF-8 bytes in runtime states as well as catalogues', () => {
    const fields = Array.from({ length: 6 }, (_, fieldIndex) => ({
      key: `mode${fieldIndex}`, label: '模式', kind: 'select',
      options: Array.from({ length: 128 }, (_, optionIndex) => ({ value: String(optionIndex), label: '界'.repeat(180) })),
    }))
    assert.ok(JSON.stringify(fields).length < 256 * 1024)
    assert.ok(new TextEncoder().encode(JSON.stringify(fields)).length > 256 * 1024)
    assert.throws(() => parsePluginRuntimeState(runtime({ configurationFields: fields })), invalidResponse)
    const raw = catalog()
    raw.manifest.configuration = fields
    assert.throws(() => parsePluginCatalogItem(raw), invalidResponse)
  })

  it('bounds catalogue/list shapes and rejects duplicate executable package identities', () => {
    assert.throws(() => parsePluginCatalog({ items: [] }), invalidResponse)
    assert.throws(() => parsePluginCatalog(null), invalidResponse)
    assert.throws(() => parsePluginCatalog([catalog(), catalog()]), invalidResponse)
    assert.throws(() => parsePluginCatalog(Array.from({ length: 129 }, (_, index) => catalog(`example.p${index}`))), invalidResponse)
    const inactive = catalog()
    inactive.compatible = false
    inactive.manifest.id = 'x'.repeat(129)
    assert.throws(() => parsePluginCatalogItem(inactive), invalidResponse)
  })
})

describe('plugin lifecycle and admitted UI contracts', () => {
  it('normalizes omitted nullable and SDK state defaults without inventing an active instance', () => {
    assert.deepEqual(parsePluginRuntimeState(runtime()), {
      ...runtime(), revision: 0, installedVersion: null, activeVersion: null, loadError: null,
      permissions: [], approvedPermissions: [], configuration: {}, activeConfiguration: null,
      configurationFields: [], activeUi: null,
    })
  })

  it('preserves a pending upgrade old active version/UI/configuration separately from desired state', () => {
    const oldUi = catalog(SECOND_PLUGIN).ui
    const raw = runtime({
      installed: true, installedVersion: '2.0.0', activeVersion: '1.0.0',
      desiredEnabled: true, admissionEnabled: true, requiresRestart: true, status: 'pending-restart',
      permissions: [CHANNEL_PERMISSION, ACCOUNT_PERMISSION], approvedPermissions: [CHANNEL_PERMISSION, ACCOUNT_PERMISSION],
      configuration: { newFlag: false }, activeConfiguration: { oldText: 'saved old setting' },
      configurationFields: [{ key: 'newFlag', label: '新设置', kind: 'boolean', defaultValue: false }],
      activeUi: oldUi,
    })
    const state = parsePluginRuntimeState(raw)
    assert.equal(state.installedVersion, '2.0.0')
    assert.equal(state.activeVersion, '1.0.0')
    assert.deepEqual(state.configuration, { newFlag: false })
    assert.deepEqual(state.activeConfiguration, { oldText: 'saved old setting' })
    assert.equal(state.activeUi.entries[0].action.id, 'probe-channel')
    assert.notEqual(state.activeUi, oldUi)
    assert.equal(state.requiresRestart, true)
  })

  it('closes disabled/tombstone UI while retaining selection and configuration, even with stale raw UI', () => {
    for (const status of ['disabled', 'uninstalled']) {
      const state = parsePluginRuntimeState(runtime({
        installed: status === 'disabled', installedVersion: '2.0.0', activeVersion: '1.0.0',
        desiredEnabled: false, admissionEnabled: false, requiresRestart: true, status,
        configuration: { defaultPrompt: 'saved selection', oldFlag: true, count: 0, optional: null },
        activeConfiguration: { defaultPrompt: 'loaded setting' },
        activeUi: { schemaVersion: 999, script: 'not executable' },
      }))
      assert.equal(state.installedVersion, '2.0.0')
      assert.equal(state.activeVersion, '1.0.0')
      assert.equal(state.activeUi, null)
      assert.deepEqual(state.configuration, { defaultPrompt: 'saved selection', oldFlag: true, count: 0, optional: null })
      assert.deepEqual(state.activeConfiguration, { defaultPrompt: 'loaded setting' })
    }
  })

  it('rejects malformed admission/state/configuration without trusting TypeScript casts', () => {
    const invalidStates = [
      runtime({ installed: true }), runtime({ admissionEnabled: true }),
      runtime({ installed: true, installedVersion: '1.0.0', desiredEnabled: true, admissionEnabled: true, activeVersion: '1.0.0' }),
      runtime({ installed: 'false' }), runtime({ status: 'ready' }), runtime({ installedVersion: 'latest' }),
      runtime({ configuration: { defaultPrompt: { nested: true } } }), runtime({ configuration: { defaultPrompt: [] } }),
      runtime({ configuration: { defaultPrompt: 'x'.repeat(4001) } }), runtime({ configuration: { password: 'not a setting' } }),
      runtime({ configuration: { count: NaN } }), runtime({ endpoint: 'uninterpreted data' }),
    ]
    for (const raw of invalidStates) assert.throws(() => parsePluginRuntimeState(raw), invalidResponse)
    assert.throws(() => parsePlugins([runtime(), runtime()]), invalidResponse)
    assert.throws(() => parsePlugins(null), invalidResponse)
  })

  it('validates generic contributions independently of an official ID and rejects executable hooks', () => {
    const contribution = { pluginId: SECOND_PLUGIN, activeVersion: '1.0.0', entry: catalog(SECOND_PLUGIN).ui.entries[0] }
    const parsed = parsePluginContributions([contribution])
    assert.equal(parsed[0].pluginId, SECOND_PLUGIN)
    assert.equal(parsed[0].entry.action.resource, 'model-test-targets')
    assert.throws(() => parsePluginContributions([{ ...contribution, activeVersion: 'latest' }]), invalidResponse)
    assert.throws(() => parsePluginContributions([contribution, contribution]), invalidResponse)
    contribution.entry.action.resource = 'https://untrusted.example.test/'
    assert.throws(() => parsePluginContributions([contribution]), invalidResponse)
  })
})

describe('bounded host probe executions and outcome conversion', () => {
  it('normalizes omitted execution/usage/report fields and SDK plan defaults', () => {
    const parsed = parsePluginExecution(execution())
    assert.deepEqual(parsed.target, {
      kind: 'channel', id: 'channel/one', model: 'vendor/model', protocol: 'responses',
      displayName: '', credentialId: null, credentialName: null,
    })
    assert.deepEqual(parsed.plan, { prompt: DEFAULT_PROMPT, maxOutputTokens: 128, timeoutMs: 45000 })
    assert.deepEqual(parsed.outcome, {
      success: false, statusCode: null, latencyMs: 0, responseText: null, errorMessage: null,
      usage: null, upstreamReportedModel: null,
    })
    assert.deepEqual(parsed.report, { success: true, title: '独立报告 <结果>', metrics: [], notices: [], text: null })
    const raw = execution()
    raw.outcome.usage = { inputTokens: 0, outputTokens: 2 }
    assert.deepEqual(parsePluginExecution(raw).outcome.usage, {
      inputTokens: 0, outputTokens: 2, cachedTokens: null, reasoningTokens: null, totalTokens: null,
    })
  })

  it('accepts exact plan caps and a65000ms account preparation envelope', () => {
    const raw = execution()
    raw.target.kind = 'oauth-account'
    raw.plan = { prompt: 'x'.repeat(4000), maxOutputTokens: 128, timeoutMs: 45000 }
    raw.outcome.latencyMs = 65000
    assert.deepEqual(parsePluginExecution(raw).plan, raw.plan)
    assert.equal(parsePluginExecution(raw).outcome.latencyMs, 65000)
  })

  it('uses outcome, target identity and plan prompt rather than an independent report verdict/text', () => {
    const raw = execution()
    raw.target.credentialId = 'opaque-key/one'
    raw.target.credentialName = '只含显示名'
    raw.outcome = {
      success: false, statusCode: 401, latencyMs: 42, responseText: '<literal response>', errorMessage: 'upstream denied',
      usage: { totalTokens: 2 }, upstreamReportedModel: 'upstream/model',
    }
    raw.report = { success: true, title: '<script>literal title</script>', text: 'not the response', metrics: [{ label: '<label>', value: '<value>' }], notices: ['<notice>'] }
    const parsed = parsePluginExecution(raw)
    const result = pluginExecutionToModelTestResult(parsed)
    assert.deepEqual(result, {
      success: false, model: 'vendor/model', protocol: 'responses', keyId: 'opaque-key/one', keyName: '只含显示名',
      statusCode: 401, latencyMs: 42, prompt: DEFAULT_PROMPT, reasoningEffort: null,
      responseText: '<literal response>', errorMessage: 'upstream denied',
      usage: { inputTokens: null, outputTokens: null, cachedTokens: null, reasoningTokens: null, totalTokens: 2 },
      upstreamReportedModel: 'upstream/model',
    })
    assert.equal(parsed.report.title, raw.report.title)
    assert.equal(parsed.report.metrics[0].label, '<label>')
    raw.outcome.success = true
    raw.report.success = false
    assert.equal(pluginExecutionToModelTestResult(parsePluginExecution(raw)).success, true)
  })

  const invalidExecutions = [
    ['missing outcome', data => { delete data.outcome }],
    ['missing success', data => { delete data.outcome.success }],
    ['string success', data => { data.outcome.success = 'false' }],
    ['overlong prompt', data => { data.plan.prompt = 'x'.repeat(4001) }],
    ['blank prompt', data => { data.plan.prompt = ' ' }],
    ['output above128', data => { data.plan.maxOutputTokens = 129 }],
    ['zero output', data => { data.plan.maxOutputTokens = 0 }],
    ['fractional output', data => { data.plan.maxOutputTokens = 1.5 }],
    ['timeout above45s', data => { data.plan.timeoutMs = 45001 }],
    ['zero timeout', data => { data.plan.timeoutMs = 0 }],
    ['target selection in plan', data => { data.plan.model = 'other-model' }],
    ['endpoint in plan', data => { data.plan.url = 'https://untrusted.example.test/' }],
    ['unknown protocol', data => { data.target.protocol = 'custom-protocol' }],
    ['credential plaintext', data => { data.target.accessToken = 'uninterpreted data' }],
    ['blank credential ID', data => { data.target.credentialId = ' ' }],
    ['negative latency', data => { data.outcome.latencyMs = -1 }],
    ['fractional latency', data => { data.outcome.latencyMs = 0.5 }],
    ['unsafe latency', data => { data.outcome.latencyMs = Number.MAX_SAFE_INTEGER + 1 }],
    ['nonfinite latency', data => { data.outcome.latencyMs = Infinity }],
    ['invalid HTTP status', data => { data.outcome.statusCode = 99 }],
    ['string HTTP status', data => { data.outcome.statusCode = '401' }],
    ['overlong response', data => { data.outcome.responseText = 'x'.repeat(16001) }],
    ['overlong error', data => { data.outcome.errorMessage = 'x'.repeat(4001) }],
    ['overlong upstream model', data => { data.outcome.upstreamReportedModel = 'x'.repeat(257) }],
    ['unknown usage field', data => { data.outcome.usage = { raw: 'not a counter' } }],
    ['string counter', data => { data.outcome.usage = { inputTokens: '1' } }],
    ['negative counter', data => { data.outcome.usage = { totalTokens: -1 } }],
    ['fractional counter', data => { data.outcome.usage = { totalTokens: 0.5 } }],
    ['unsafe counter', data => { data.outcome.usage = { totalTokens: Number.MAX_SAFE_INTEGER + 1 } }],
    ['report HTML hook', data => { data.report.html = '<div>not a renderer</div>' }],
    ['overlong report title', data => { data.report.title = 'x'.repeat(257) }],
    ['too many metrics', data => { data.report.metrics = Array(33).fill({ label: 'l', value: 'v' }) }],
    ['overlong metric label', data => { data.report.metrics = [{ label: 'x'.repeat(129), value: 'v' }] }],
    ['overlong metric value', data => { data.report.metrics = [{ label: 'l', value: 'x'.repeat(513) }] }],
    ['metric callback', data => { data.report.metrics = [{ label: 'l', value: 'v', onClick: 'not executable' }] }],
    ['too many notices', data => { data.report.notices = Array(17).fill('notice') }],
    ['overlong notice', data => { data.report.notices = ['x'.repeat(2001)] }],
    ['overlong report text', data => { data.report.text = 'x'.repeat(16001) }],
  ]
  for (const [name, mutate] of invalidExecutions) {
    it(`rejects ${name}`, () => {
      const raw = execution()
      mutate(raw)
      assert.throws(() => parsePluginExecution(raw), invalidResponse)
    })
  }
})

describe('bounded authorized model-test-target resources', () => {
  it('parses channel and account resources without exposing endpoint or credential secrets', () => {
    assert.deepEqual(parsePluginTargets([target()], { kind: 'channel', id: 'channel/one' }), [target()])
    const context = { kind: 'oauth-account', id: 'account/one' }
    const account = target({ channelId: context.id, credentials: [{ id: context.id, name: '账号显示名', priority: -1 }] })
    assert.deepEqual(parsePluginTargets([account], context), [account])
    assert.deepEqual(parsePluginTargets([]), [])
  })

  it('rejects malformed protocols, credentials, duplicate/bounded shapes and cross-context targets', () => {
    const mutations = [
      data => { data.channelEnabled = 'true' }, data => { data.model = 'x'.repeat(257) },
      data => { data.protocols = ['unknown'] }, data => { data.protocols = ['responses', 'responses'] },
      data => { data.credentials[0].priority = 0.5 }, data => { data.credentials[0].priority = 2147483648 },
      data => { data.credentials[0].apiKey = 'not a resource field' }, data => { data.credentials[0].endpoint = 'not a resource field' },
      data => { data.credentials.push(structuredClone(data.credentials[0])) }, data => { data.endpoint = 'not a resource field' },
      data => { data.credentials = Array.from({ length: 513 }, (_, index) => ({ id: `k${index}`, name: '凭据', priority: 0 })) },
    ]
    for (const mutate of mutations) {
      const raw = target()
      mutate(raw)
      assert.throws(() => parsePluginTargets([raw]), invalidResponse)
    }
    assert.throws(() => parsePluginTargets([target(), target()]), invalidResponse)
    assert.throws(() => parsePluginTargets([target()], { kind: 'channel', id: 'other/channel' }), invalidResponse)
    assert.throws(() => parsePluginTargets([target()], { kind: 'oauth-account', id: 'channel/one' }), invalidResponse)
    assert.throws(() => parsePluginTargets({ targets: [] }), invalidResponse)
    assert.throws(() => parsePluginTargets(new Array(1)), invalidResponse)
  })

  it('accepts 10000 dense unique valid targets and rejects the same list at 10001', () => {
    const targets = Array.from({ length: 10001 }, (_, index) => target({ channelId: `channel-${index}`, model: `model-${index}` }))
    assert.equal(parsePluginTargets(targets.slice(0, 10000)).length, 10000)
    assert.throws(() => parsePluginTargets(targets), invalidResponse)
  })
})

describe('generic plugin API paths and existing session semantics', () => {
  it('uses all ten fixed endpoints, exact payloads, cookies/CSRF and signal-only read options', async t => {
    const context = { kind: 'channel', id: 'channel/a +?#& 中文' }
    const unsafeLookingId = 'plugin/a +?#& 中文'
    const encodedId = encodeURIComponent(unsafeLookingId)
    const signal = new AbortController().signal
    const csrf = `csrf ${globalThis.crypto.randomUUID()}/+&=`
    const install = { version: '1.0.0', approvedPermissions: [CHANNEL_PERMISSION, ACCOUNT_PERMISSION], expectedSha256: 'a'.repeat(64) }
    const enable = { enabled: true, approvedPermissions: [CHANNEL_PERMISSION, ACCOUNT_PERMISSION] }
    const configure = { values: { defaultPrompt: 'saved prompt', flag: false, count: 0, optional: null }, expectedRevision: 7, installedVersion: '1.0.0' }
    const invoke = invocation(context)
    const hostExecution = execution()
    hostExecution.target.id = context.id
    const contribution = { pluginId: SECOND_PLUGIN, activeVersion: '1.0.0', entry: catalog(SECOND_PLUGIN).ui.entries[0] }
    const responses = [
      [catalog(SECOND_PLUGIN)], [runtime()], runtime(), [contribution],
      runtime({ installed: true, installedVersion: '1.0.0', status: 'disabled' }),
      runtime({ installed: true, installedVersion: '1.0.0', desiredEnabled: true, requiresRestart: true, status: 'pending-restart' }),
      runtime({ configuration: configure.values }), runtime({ installedVersion: '1.0.0', configuration: configure.values }),
      [target({ channelId: context.id })], hostExecution,
    ]
    const calls = []
    replaceGlobals(t, {
      document: { cookie: `other=value; gw_csrf=${encodeURIComponent(csrf)}; tail=value` },
      fetch: async (input, init = {}) => { calls.push({ url: String(input), ...init }); return json(responses.shift()) },
    })
    const readOptions = { signal, method: 'DELETE', body: 'ignored', headers: { 'X-Unsafe': 'ignored' } }
    assert.equal((await api.listPluginCatalog(readOptions))[0].manifest.id, SECOND_PLUGIN)
    await api.listPlugins(readOptions)
    await api.getPlugin(unsafeLookingId, readOptions)
    await api.getPluginContributions(readOptions)
    await api.installPlugin(unsafeLookingId, install)
    await api.setPluginEnabled(unsafeLookingId, enable)
    await api.configurePlugin(unsafeLookingId, configure)
    const tombstone = await api.uninstallPlugin(unsafeLookingId)
    assert.equal(tombstone.installed, false)
    assert.equal(tombstone.installedVersion, '1.0.0')
    assert.deepEqual(tombstone.configuration, configure.values)
    await api.listPluginTargets(unsafeLookingId, context, readOptions)
    await api.invokePlugin(unsafeLookingId, invoke, { ...readOptions, method: 'DELETE' })
    assert.deepEqual(calls.map(call => [call.method, call.url]), [
      ['GET', '/admin/api/plugin-catalog'], ['GET', '/admin/api/plugins'],
      ['GET', `/admin/api/plugins/${encodedId}`], ['GET', '/admin/api/plugins/ui'],
      ['PUT', `/admin/api/plugins/${encodedId}/installation`], ['PUT', `/admin/api/plugins/${encodedId}/enabled`],
      ['PUT', `/admin/api/plugins/${encodedId}/configuration`], ['DELETE', `/admin/api/plugins/${encodedId}`],
      ['GET', `/admin/api/plugins/${encodedId}/resources/model-test-targets?${new URLSearchParams({ contextKind: context.kind, contextId: context.id })}`],
      ['POST', `/admin/api/plugins/${encodedId}/invocations`],
    ])
    for (const call of calls) {
      assert.equal(call.credentials, 'include')
      assert.equal(call.headers.get('X-Unsafe'), null)
      if (call.method === 'GET') {
        assert.equal(call.body, undefined)
        assert.equal(call.headers.get('X-CSRF-Token'), null)
        assert.equal(call.headers.get('Content-Type'), null)
        assert.equal(call.signal, signal)
      } else {
        assert.equal(call.headers.get('X-CSRF-Token'), csrf)
        assert.equal(call.headers.get('Content-Type'), call.body == null ? null : 'application/json')
      }
    }
    assert.deepEqual(JSON.parse(calls[4].body), install)
    assert.deepEqual(JSON.parse(calls[5].body), enable)
    assert.deepEqual(JSON.parse(calls[6].body), configure)
    assert.equal(calls[7].body, undefined)
    assert.deepEqual(JSON.parse(calls[9].body), invoke)
    assert.equal(calls[9].signal, signal)
    assert.equal(responses.length, 0)
  })

  it('works for a second compatible plugin with omitted enable permissions and an unscoped resource query', async t => {
    const calls = []
    replaceGlobals(t, {
      document: { cookie: '' },
      fetch: async (input, init = {}) => {
        calls.push({ url: String(input), ...init })
        if (String(input).endsWith('/resources/model-test-targets')) return json([target()])
        if (String(input).endsWith('/invocations')) return json(execution())
        return json(runtime())
      },
    })
    await api.setPluginEnabled(SECOND_PLUGIN, { enabled: false })
    assert.deepEqual(JSON.parse(calls[0].body), { enabled: false })
    assert.equal(calls[0].headers.get('X-CSRF-Token'), null)
    assert.deepEqual(await api.listPluginTargets(SECOND_PLUGIN), [target()])
    assert.equal(calls[1].url, `/admin/api/plugins/${SECOND_PLUGIN}/resources/model-test-targets`)
    assert.equal((await api.invokePlugin(SECOND_PLUGIN, invocation())).pluginId, SECOND_PLUGIN)
  })

  it('dispatches gateway HTTP401 once but does not dispatch for upstream outcome401 in HTTP200', async t => {
    const events = []
    const upstream = execution()
    upstream.outcome.statusCode = 401
    const responses = [json({ error: { code: 'admin_session_expired', message: 'session expired' } }, 401), json(upstream)]
    replaceGlobals(t, {
      document: { cookie: '' },
      window: { dispatchEvent: event => { events.push(event.type); return true } },
      CustomEvent: class { constructor(type) { this.type = type } },
      fetch: async () => responses.shift(),
    })
    await assert.rejects(api.invokePlugin(SECOND_PLUGIN, invocation()), error => {
      assert.equal(error instanceof ApiError, true)
      assert.equal(error.status, 401)
      assert.equal(error.code, 'admin_session_expired')
      assert.equal(error.message, 'session expired')
      return true
    })
    assert.deepEqual(events, ['gateway:unauthorized'])
    const parsed = await api.invokePlugin(SECOND_PLUGIN, invocation())
    assert.equal(parsed.outcome.statusCode, 401)
    assert.equal(parsed.report.success, true)
    assert.equal(pluginExecutionToModelTestResult(parsed).success, false)
    assert.deepEqual(events, ['gateway:unauthorized'])
    assert.equal(responses.length, 0)
  })

  it('preserves callback HTTP502 ApiError status/code without converting or retrying it', async t => {
    let calls = 0
    const events = []
    replaceGlobals(t, {
      document: { cookie: '' }, window: { dispatchEvent: event => { events.push(event.type); return true } },
      fetch: async () => { calls++; return json({ error: { code: 'PLUGIN_CALLBACK_FAILED', message: 'callback failed' } }, 502) },
    })
    await assert.rejects(api.invokePlugin(SECOND_PLUGIN, invocation()), error => {
      assert.equal(error instanceof ApiError, true)
      assert.equal(error.status, 502)
      assert.equal(error.code, 'PLUGIN_CALLBACK_FAILED')
      assert.equal(error.message, 'callback failed')
      return true
    })
    assert.equal(calls, 1)
    assert.deepEqual(events, [])
  })

  it('runs response parsers for every plugin endpoint instead of passing malformed success payloads through', async t => {
    let calls = 0
    replaceGlobals(t, {
      document: { cookie: '' }, fetch: async () => { calls++; return json({ unexpected: true }) },
    })
    for (const action of [
      () => api.listPluginCatalog(), () => api.listPlugins(), () => api.getPlugin(SECOND_PLUGIN),
      () => api.getPluginContributions(), () => api.installPlugin(SECOND_PLUGIN, { version: '1.0.0', approvedPermissions: [] }),
      () => api.setPluginEnabled(SECOND_PLUGIN, { enabled: false }), () => api.configurePlugin(SECOND_PLUGIN, { values: {} }),
      () => api.uninstallPlugin(SECOND_PLUGIN), () => api.listPluginTargets(SECOND_PLUGIN),
      () => api.invokePlugin(SECOND_PLUGIN, invocation()),
    ]) await assert.rejects(action(), invalidResponse)
    assert.equal(calls, 10)
  })

  it('preserves legacy empty-list/204 behavior while requiring an authoritative plugin uninstall state', async t => {
    const responses = [json(null), new Response(null, { status: 204 }), new Response(null, { status: 204 })]
    replaceGlobals(t, { document: { cookie: '' }, fetch: async () => responses.shift() })
    assert.deepEqual(await api.listChannels(), [])
    assert.equal(await api.logout(), null)
    await assert.rejects(api.uninstallPlugin(SECOND_PLUGIN), invalidResponse)
  })

  it('retains the existing JSON/fetch failure handling without retrying or dispatching unauthorized', async t => {
    let calls = 0
    const events = []
    replaceGlobals(t, {
      document: { cookie: '' }, window: { dispatchEvent: event => { events.push(event.type); return true } },
      fetch: async () => {
        calls++
        if (calls === 1) return new Response('{invalid JSON')
        throw new Error('offline')
      },
    })
    await assert.rejects(api.listPluginCatalog(), invalidResponse)
    await assert.rejects(api.invokePlugin(SECOND_PLUGIN, invocation()), error => {
      assert.equal(error instanceof ApiError, false)
      assert.equal(error.message, '无法连接网关，请确认服务已启动')
      return true
    })
    assert.equal(calls, 2)
    assert.deepEqual(events, [])
  })
})
