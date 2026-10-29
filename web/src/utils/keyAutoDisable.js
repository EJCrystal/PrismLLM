const ACTIONS = new Set(['TEMPORARY', 'PERMANENT'])
const RULE_ID = /^[a-z0-9_]{1,40}$/

/** 后端内置规则的镜像，仅用于首次渲染和加载失败时的占位。 */
export const DEFAULT_KEY_AUTO_DISABLE_SETTINGS = Object.freeze({
  enabled: true,
  rules: [
    {
      id: 'credential_invalid',
      name: '凭据失效',
      statusCodes: [401, 403],
      statusCodeAtLeast: null,
      bodyPatterns: ['invalid[\\s_-]*(x-)?api[\\s_-]?key', 'incorrect api key', 'authentication_error', 'invalid_grant'],
      threshold: 2,
      windowMinutes: 5,
      streak: true,
      action: 'PERMANENT',
      disableMinutes: null,
      includeTransportFailures: false,
    },
    {
      id: 'account_blocked',
      name: '上游账号被停',
      statusCodes: [401, 403],
      statusCodeAtLeast: null,
      bodyPatterns: ['account[^\\n]{0,40}(disabled|suspended|deactivated)', '\\bbilling\\b', '\\bpayment\\b'],
      threshold: 1,
      windowMinutes: 5,
      streak: true,
      action: 'PERMANENT',
      disableMinutes: null,
      includeTransportFailures: false,
    },
    {
      id: 'auth_rejected',
      name: '鉴权被拒',
      statusCodes: [401, 403],
      statusCodeAtLeast: null,
      bodyPatterns: [],
      threshold: 3,
      windowMinutes: 5,
      streak: false,
      action: 'TEMPORARY',
      disableMinutes: 30,
      includeTransportFailures: false,
    },
    {
      id: 'upstream_unstable',
      name: '上游不稳定',
      statusCodes: [429],
      statusCodeAtLeast: 500,
      bodyPatterns: [],
      threshold: 8,
      windowMinutes: 5,
      streak: false,
      action: 'TEMPORARY',
      disableMinutes: 10,
      includeTransportFailures: true,
    },
  ],
})

function integer(value, min, max, label) {
  const number = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  if (!Number.isInteger(number) || number < min || number > max) {
    throw new Error(`${label}必须是 ${min}..${max} 之间的整数`)
  }
  return number
}

function optionalInteger(value, min, max, label) {
  if (value === null || value === undefined || value === '') return null
  return integer(value, min, max, label)
}

function normalizeRule(rule, index) {
  if (!rule || typeof rule !== 'object') throw new Error(`第 ${index + 1} 条规则格式无效`)
  const id = String(rule.id ?? '').trim()
  if (!RULE_ID.test(id)) throw new Error(`第 ${index + 1} 条规则的 id 只能包含小写字母、数字和下划线`)
  const name = String(rule.name ?? '').trim()
  if (!name || name.length > 40) throw new Error(`第 ${index + 1} 条规则的名称长度必须在 1..40 之间`)
  const statusCodes = Array.isArray(rule.statusCodes)
    ? rule.statusCodes.map((code) => integer(code, 100, 599, `规则「${name}」的状态码`))
    : []
  const statusCodeAtLeast = optionalInteger(rule.statusCodeAtLeast, 100, 599, `规则「${name}」的状态码下限`)
  const bodyPatterns = Array.isArray(rule.bodyPatterns)
    ? rule.bodyPatterns.map((pattern) => String(pattern).trim()).filter(Boolean)
    : []
  if (bodyPatterns.length > 20) throw new Error(`规则「${name}」最多 20 个匹配模式`)
  bodyPatterns.forEach((pattern) => {
    if (pattern.length > 200) throw new Error(`规则「${name}」的匹配模式不能超过 200 个字符`)
  })
  const includeTransportFailures = rule.includeTransportFailures === true
  if (!statusCodes.length && statusCodeAtLeast == null && !includeTransportFailures) {
    throw new Error(`规则「${name}」至少需要状态码、状态码下限或传输层失败之一`)
  }
  const action = String(rule.action ?? '')
  if (!ACTIONS.has(action)) throw new Error(`规则「${name}」的动作必须是 TEMPORARY 或 PERMANENT`)
  if (action === 'PERMANENT' && rule.disableMinutes !== null && rule.disableMinutes !== undefined && rule.disableMinutes !== '') {
    throw new Error(`规则「${name}」是永久停用，不能配置临时停用时长`)
  }
  const disableMinutes = action === 'TEMPORARY'
    ? integer(rule.disableMinutes, 1, 10_080, `规则「${name}」的临时停用时长`)
    : null
  return {
    id,
    name,
    statusCodes: [...new Set(statusCodes)],
    statusCodeAtLeast,
    bodyPatterns,
    threshold: integer(rule.threshold, 1, 1000, `规则「${name}」的触发阈值`),
    windowMinutes: integer(rule.windowMinutes, 1, 1440, `规则「${name}」的统计窗口`),
    streak: rule.streak !== false,
    action,
    disableMinutes,
    includeTransportFailures,
  }
}

/** 兼容旧响应：没有 rules 时把两个标量门槛翻译成两条永久规则。 */
function legacyRules(value) {
  return [
    {
      id: 'legacy_auth_streak',
      name: '连续鉴权失败',
      statusCodes: [401, 403],
      statusCodeAtLeast: null,
      bodyPatterns: [],
      threshold: value.authFailureThreshold,
      windowMinutes: 1440,
      streak: true,
      action: 'PERMANENT',
      disableMinutes: null,
      includeTransportFailures: false,
    },
    {
      id: 'legacy_failure_window',
      name: '时间窗口内多次失败',
      statusCodes: [401, 403, 429],
      statusCodeAtLeast: 500,
      bodyPatterns: [],
      threshold: value.failureThreshold,
      windowMinutes: value.failureWindowMinutes,
      streak: false,
      action: 'PERMANENT',
      disableMinutes: null,
      includeTransportFailures: true,
    },
  ]
}

/** 同时校验读取结果和保存输入，避免加载失败后覆盖线上规则。 */
export function normalizeKeyAutoDisableSettings(value) {
  if (typeof value?.enabled !== 'boolean') throw new Error('自动禁用设置格式无效，请重新加载')
  const rawRules = Array.isArray(value.rules)
    ? value.rules
    : (value.authFailureThreshold != null && value.failureThreshold != null && value.failureWindowMinutes != null
      ? legacyRules(value)
      : null)
  if (!rawRules?.length) throw new Error('自动禁用设置缺少规则列表，请重新加载')
  const rules = rawRules.map(normalizeRule)
  const ids = new Set()
  for (const rule of rules) {
    if (ids.has(rule.id)) throw new Error(`规则 id 不能重复：${rule.id}`)
    ids.add(rule.id)
  }
  if (rules.length > 50) throw new Error('最多配置 50 条自动禁用规则')
  return { enabled: value.enabled, rules }
}

export function buildKeyAutoDisableSettingsPayload(form) {
  return normalizeKeyAutoDisableSettings({ enabled: form.enabled, rules: form.rules })
}

export function keyAutoDisableRuleLabel(settings) {
  if (!settings) return '—'
  const rules = Array.isArray(settings.rules)
    ? settings.rules
    : (settings.authFailureThreshold != null ? legacyRules(settings) : [])
  if (!rules.length) return settings.enabled === false ? '已关闭自动禁用' : '—'
  const summary = rules.map((rule) => rule.name).join('、')
  return settings.enabled === false ? `已关闭自动禁用（${summary}）` : summary
}

/** 新建规则草稿；id 只用于前端展示与后端计数键。 */
export function newKeyDisableRule(rules = []) {
  const used = new Set(rules.map((rule) => rule.id))
  let index = 1
  while (used.has(`custom_${index}`)) index += 1
  return {
    id: `custom_${index}`,
    name: '自定义规则',
    statusCodesText: '401,403',
    statusCodeAtLeast: null,
    bodyPatternsText: '',
    threshold: 3,
    windowMinutes: 5,
    streak: true,
    action: 'TEMPORARY',
    disableMinutes: 30,
    includeTransportFailures: false,
  }
}

/** 规则草稿转编辑字段。 */
export function toKeyDisableRuleDraft(rule) {
  return {
    ...rule,
    statusCodesText: rule.statusCodes.join(','),
    bodyPatternsText: rule.bodyPatterns.join('\n'),
  }
}

/** 编辑字段转提交规则。 */
export function fromKeyDisableRuleDraft(draft) {
  return {
    ...draft,
    disableMinutes: draft.action === 'TEMPORARY' ? draft.disableMinutes : null,
    statusCodes: String(draft.statusCodesText ?? '')
      .split(',')
      .map((code) => code.trim())
      .filter(Boolean),
    bodyPatterns: String(draft.bodyPatternsText ?? '')
      .split('\n')
      .map((pattern) => pattern.trim())
      .filter(Boolean),
  }
}
