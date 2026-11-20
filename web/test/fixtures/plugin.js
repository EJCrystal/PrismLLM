// Plain synthetic UI/API data only. These helpers never validate or load JVM code, ZIPs or disk
// catalogues; a mocked API success is NOT evidence that any backend package would be accepted.
export const clonePluginData = value => JSON.parse(JSON.stringify(value))

export function makeField(key, label, kind, overrides = {}) {
  return {
    key, label, kind, required: false, defaultValue: null, help: '', maxLength: null,
    min: null, max: null, options: [], ...clonePluginData(overrides),
  }
}

const promptField = () => makeField('prompt', '本次提示词', 'textarea', { maxLength: 4000, help: '留空沿用生效版本的默认提示词。' })
const modelField = () => makeField('model', '模型', 'model', { required: true })
const protocolField = () => makeField('protocol', '协议', 'protocol', { required: true })
const credentialField = () => makeField('keyId', '上游凭据', 'credential', { help: '只显示保存的名称和不透明 ID。' })
const scopeField = () => makeField('scope', '检测范围', 'scope', {
  required: true, defaultValue: 'single', options: [{ value: 'single', label: '当前目标' }, { value: 'all', label: '全部目标' }],
})
const syntheticFields = () => [
  makeField('label', '观察标签', 'text', { required: true, defaultValue: 'synthetic', maxLength: 80, help: '普通文本，不执行表达式。' }),
  makeField('memo', '观察备注', 'textarea', { defaultValue: 'plain fixture notes', maxLength: 512 }),
  makeField('verbose', '详细报告', 'boolean', { defaultValue: false }),
  makeField('samples', '样本数量', 'number', { required: true, defaultValue: 2, min: 1, max: 5 }),
  makeField('style', '报告风格', 'select', { required: true, defaultValue: 'brief', options: [{ value: 'brief', label: '简洁' }, { value: 'full', label: '完整' }] }),
]

export function makePluginFixture(options = {}) {
  const id = options.id ?? 'acme.synthetic'
  const version = options.version ?? '1.0.0'
  const official = options.official === true
  const configurationFields = options.configurationFields ?? [
    makeField('defaultPrompt', '默认提示词', 'textarea', { required: true, defaultValue: '请只回复 OK', maxLength: 4000 }),
    ...(official ? [] : [makeField('threshold', '配置阈值', 'number', { defaultValue: 0.5, min: 0, max: 1 })]),
  ]
  const extras = options.extraFields ?? (official ? [] : syntheticFields())
  const sharedFields = [modelField(), protocolField(), promptField(), ...extras]
  const notes = [
    '单次请求最多等待 45 秒，最多 128 个输出 token；全部目标最多两个请求并发。',
    '不写生产请求日志、不消耗调用方密钥额度；实际请求仍可能产生费用。',
  ]
  const action = (actionId, label, fields, supportsBatch) => ({
    id: actionId, label, fields: clonePluginData(fields), resource: 'model-test-targets',
    supportsBatch, resultKind: 'probe-v1', notes: [...notes],
  })
  const manifest = {
    id, version, displayName: options.displayName ?? (official ? '官方连通性诊断' : 'Synthetic Observatory'),
    description: options.description ?? `Declarative test-only package ${id} ${version}`,
    schemaVersion: 1, apiVersion: 1,
    permissions: options.permissions ?? ['diagnostics.channel.probe', 'diagnostics.oauth-account.probe'],
    configuration: clonePluginData(configurationFields),
  }
  const ui = {
    schemaVersion: 1,
    entries: [
      {
        id: official ? 'connectivity-channel' : 'synthetic-channel', label: official ? '连通性' : '合成诊断',
        placement: 'channel-tests', contexts: ['channel'], icon: 'bolt',
        action: action(official ? 'probe-channel' : 'inspect-channel', official ? '检测连通性' : '执行合成诊断',
          options.channelFields ?? [...sharedFields, credentialField(), scopeField()], true),
      },
      {
        id: official ? 'connectivity-account' : 'synthetic-account', label: official ? '连通性' : '账号合成诊断',
        placement: 'oauth-account-tests', contexts: ['oauth-account'], icon: 'bolt',
        action: action(official ? 'probe-account' : 'inspect-account', official ? '检测账号连通性' : '执行账号合成诊断',
          options.accountFields ?? sharedFields, false),
      },
      {
        id: official ? 'connectivity-page' : 'synthetic-page', label: official ? '模型连通性诊断' : '独立合成诊断',
        placement: 'plugin-page', contexts: ['channel'], icon: 'bolt',
        action: action(official ? 'probe-global' : 'inspect-global', official ? '检测模型连通性' : '执行独立合成诊断',
          options.pageFields ?? [...sharedFields, credentialField(), scopeField()], true),
      },
    ],
  }
  const configuration = Object.fromEntries(configurationFields.map(field => [field.key, field.defaultValue]))
  const catalog = {
    manifest: clonePluginData(manifest), ui: clonePluginData(ui), sha256: options.sha256 ?? 'a'.repeat(64),
    source: 'bundled', compatible: true, compatibilityReason: null, ...clonePluginData(options.catalog ?? {}),
  }
  const state = {
    id, revision: 0, displayName: manifest.displayName, description: manifest.description, source: 'bundled', installed: true,
    installedVersion: version, activeVersion: version, desiredEnabled: true, admissionEnabled: true,
    requiresRestart: false, status: 'active', loadError: null, permissions: [...manifest.permissions],
    approvedPermissions: [...manifest.permissions], configuration: clonePluginData(configuration),
    activeConfiguration: clonePluginData(configuration), configurationFields: clonePluginData(configurationFields),
    activeUi: clonePluginData(ui), ...clonePluginData(options.state ?? {}),
  }
  const contributions = ui.entries.map(entry => ({ pluginId: id, activeVersion: version, entry: clonePluginData(entry) }))
  return {
    manifest, ui, catalog, state, contributions,
    contribution: contributions[0], accountContribution: contributions[1], pageContribution: contributions[2],
  }
}

/** Wrapper compatibility is tested with normal descriptors, not a hardcoded execution dispatcher. */
export function makeOfficialPluginFixture(options = {}) {
  return makePluginFixture({ ...options, id: 'prism.connectivity', official: true })
}

export function makeTarget(overrides = {}) {
  return {
    channelId: 'channel-1', channelName: '合成渠道', channelEnabled: true, model: 'test-model',
    protocols: ['chat_completions', 'responses'], credentials: [{ id: 'opaque-key-1', name: '合成凭据', priority: 1 }],
    ...clonePluginData(overrides),
  }
}

export function makeExecution(options = {}) {
  const resource = options.target ?? makeTarget()
  const context = options.context ?? { kind: 'channel', id: resource.channelId }
  const values = options.values ?? {}
  const credential = resource.credentials.find(item => item.id === values.keyId) ?? resource.credentials[0]
  const success = options.outcome?.success ?? true
  const execution = {
    pluginId: options.pluginId ?? 'acme.synthetic', activeVersion: options.activeVersion ?? '1.0.0',
    target: {
      kind: context.kind, id: context.id, model: values.model ?? resource.model,
      protocol: values.protocol ?? resource.protocols[0], displayName: resource.channelName,
      credentialId: context.kind === 'channel' ? credential?.id ?? null : context.id,
      credentialName: context.kind === 'channel' ? credential?.name ?? null : '账号原生授权',
    },
    plan: { prompt: typeof values.prompt === 'string' ? values.prompt : '请只回复 OK', maxOutputTokens: 128, timeoutMs: 45000 },
    outcome: {
      success, statusCode: success ? 200 : 401, latencyMs: 25, responseText: success ? 'OK' : null,
      errorMessage: success ? null : 'Synthetic upstream rejection',
      usage: { inputTokens: 1, outputTokens: 1, cachedTokens: null, reasoningTokens: null, totalTokens: 2 },
      upstreamReportedModel: 'reported-synthetic-model', ...clonePluginData(options.outcome ?? {}),
    },
    report: {
      success, title: success ? '合成诊断完成' : '合成诊断失败',
      metrics: [{ label: '观测结果', value: 'synthetic only' }], notices: ['这是普通测试数据，不证明模型身份。'],
      text: success ? 'Synthetic report text' : 'Synthetic failure report', ...clonePluginData(options.report ?? {}),
    },
  }
  return clonePluginData(execution)
}
