import type {
  ChannelKeyDisablePolicyRequest,
  ChannelKeyDisablePolicyResponse,
  KeyAutoDisableSettingsRequest,
  KeyAutoDisableSettingsResponse,
  KeyDisableRule,
} from '../types/api'

/** 全局设置与渠道覆盖共用的策略形状。 */
export type ChannelKeyDisablePolicySettings = KeyAutoDisableSettingsRequest

export interface KeyDisableRuleDraft extends Omit<KeyDisableRule, 'statusCodes' | 'bodyPatterns' | 'statusCodesText' | 'bodyPatternsText'> {
  statusCodesText: string
  bodyPatternsText: string
}

const ACTIONS = new Set(['TEMPORARY', 'PERMANENT'])
const RULE_ID = /^[a-z0-9_]{1,40}$/

/** 后端内置规则的镜像，仅用于首次渲染和加载失败时的占位。 */
export const DEFAULT_KEY_AUTO_DISABLE_SETTINGS: Readonly<KeyAutoDisableSettingsRequest> = Object.freeze({
  enabled: true,
  rules: [
    {
      id: 'insufficient_balance',
      name: '账户余额不足',
      statusCodes: [401, 402, 403],
      statusCodeAtLeast: null,
      bodyPatterns: ['INSUFFICIENT_BALANCE'],
      threshold: 1,
      windowMinutes: 5,
      streak: true,
      action: 'PERMANENT' as const,
      disableMinutes: null,
      includeTransportFailures: false,
    },
    {
      id: 'credential_invalid',
      name: '凭据失效',
      statusCodes: [401, 403],
      statusCodeAtLeast: null,
      bodyPatterns: ['invalid[\\s_-]*(x-)?api[\\s_-]?key', 'incorrect api key', 'authentication_error', 'invalid_grant'],
      threshold: 2,
      windowMinutes: 5,
      streak: true,
      action: 'PERMANENT' as const,
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
      action: 'PERMANENT' as const,
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
      action: 'TEMPORARY' as const,
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
      action: 'TEMPORARY' as const,
      disableMinutes: 10,
      includeTransportFailures: true,
    },
  ],
})

function integer(value: unknown, min: number, max: number, label: string): number {
  const number = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  if (!Number.isInteger(number) || number < min || number > max) {
    throw new Error(`${label}必须是 ${min}..${max} 之间的整数`)
  }
  return number
}

function optionalInteger(value: unknown, min: number, max: number, label: string): number | null {
  if (value === null || value === undefined || value === '') return null
  return integer(value, min, max, label)
}

function normalizeRule(rule: unknown, index: number): KeyDisableRule {
  if (!rule || typeof rule !== 'object') throw new Error(`第 ${index + 1} 条规则格式无效`)
  const source = rule as Record<string, unknown>
  const id = String(source.id ?? '').trim()
  if (!RULE_ID.test(id)) throw new Error(`第 ${index + 1} 条规则的 id 只能包含小写字母、数字和下划线`)
  const name = String(source.name ?? '').trim()
  if (!name || name.length > 40) throw new Error(`第 ${index + 1} 条规则的名称长度必须在 1..40 之间`)
  const statusCodes = Array.isArray(source.statusCodes)
    ? source.statusCodes.map((code) => integer(code, 100, 599, `规则「${name}」的状态码`))
    : []
  const statusCodeAtLeast = optionalInteger(source.statusCodeAtLeast, 100, 599, `规则「${name}」的状态码下限`)
  const bodyPatterns = Array.isArray(source.bodyPatterns)
    ? source.bodyPatterns.map((pattern) => String(pattern).trim()).filter(Boolean)
    : []
  if (bodyPatterns.length > 20) throw new Error(`规则「${name}」最多 20 个匹配模式`)
  bodyPatterns.forEach((pattern) => {
    if (pattern.length > 200) throw new Error(`规则「${name}」的匹配模式不能超过 200 个字符`)
  })
  const includeTransportFailures = source.includeTransportFailures === true
  if (!statusCodes.length && statusCodeAtLeast == null && !includeTransportFailures) {
    throw new Error(`规则「${name}」至少需要状态码、状态码下限或传输层失败之一`)
  }
  const action = String(source.action ?? '')
  if (!ACTIONS.has(action)) throw new Error(`规则「${name}」的动作必须是 TEMPORARY 或 PERMANENT`)
  if (action === 'PERMANENT' && source.disableMinutes !== null && source.disableMinutes !== undefined && source.disableMinutes !== '') {
    throw new Error(`规则「${name}」是永久停用，不能配置临时停用时长`)
  }
  const disableMinutes = action === 'TEMPORARY'
    ? integer(source.disableMinutes, 1, 10_080, `规则「${name}」的临时停用时长`)
    : null
  return {
    id,
    name,
    statusCodes: [...new Set(statusCodes)],
    statusCodeAtLeast,
    bodyPatterns,
    threshold: integer(source.threshold, 1, 1000, `规则「${name}」的触发阈值`),
    windowMinutes: integer(source.windowMinutes, 1, 1440, `规则「${name}」的统计窗口`),
    streak: source.streak !== false,
    action: action as 'TEMPORARY' | 'PERMANENT',
    disableMinutes,
    includeTransportFailures,
  }
}

/** 兼容旧响应：没有 rules 时把两个标量门槛翻译成两条永久规则。 */
interface LegacySettings extends Partial<KeyAutoDisableSettingsResponse> {
  authFailureThreshold?: number
  failureThreshold?: number
  failureWindowMinutes?: number
}

function isLegacySettings(value: Partial<KeyAutoDisableSettingsResponse> | KeyAutoDisableSettingsRequest | LegacySettings): value is LegacySettings & Required<Pick<LegacySettings, 'authFailureThreshold' | 'failureThreshold' | 'failureWindowMinutes'>> {
  const legacy = value as LegacySettings
  return legacy.authFailureThreshold != null && legacy.failureThreshold != null && legacy.failureWindowMinutes != null
}

function legacyRules(value: Required<Pick<LegacySettings, 'authFailureThreshold' | 'failureThreshold' | 'failureWindowMinutes'>>): KeyDisableRule[] {
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
      action: 'PERMANENT' as const,
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
      action: 'PERMANENT' as const,
      disableMinutes: null,
      includeTransportFailures: true,
    },
  ]
}

/** 同时校验读取结果和保存输入，避免加载失败后覆盖线上规则。 */
export function normalizeKeyAutoDisableSettings(value: Partial<KeyAutoDisableSettingsResponse> | KeyAutoDisableSettingsRequest | LegacySettings | null | undefined): KeyAutoDisableSettingsRequest {
  if (typeof value?.enabled !== 'boolean') throw new Error('自动禁用设置格式无效，请重新加载')
  const rawRules = Array.isArray(value.rules)
    ? value.rules
    : (isLegacySettings(value)
      ? legacyRules(value)
      : null)
  // 关闭自动禁用时不需要规则；启用时缺规则说明响应不完整，不能拿默认值覆盖线上配置。
  if (!rawRules && value.enabled) throw new Error('自动禁用设置缺少规则列表，请重新加载')
  const rules = (rawRules ?? []).map(normalizeRule)
  if (value.enabled && !rules.length) throw new Error('启用自动禁用时至少需要一条规则')
  const ids = new Set<string>()
  for (const rule of rules) {
    if (ids.has(rule.id)) throw new Error(`规则 id 不能重复：${rule.id}`)
    ids.add(rule.id)
  }
  if (rules.length > 50) throw new Error('最多配置 50 条自动禁用规则')
  return { enabled: value.enabled, rules }
}

export function buildKeyAutoDisableSettingsPayload(form: { enabled: boolean, rules: KeyDisableRule[] }): KeyAutoDisableSettingsRequest {
  return normalizeKeyAutoDisableSettings({ enabled: form.enabled, rules: form.rules })
}

/** 渠道覆盖沿用同一套校验；`override` 为 null 表示恢复继承全局。 */
export function buildChannelKeyDisablePolicyPayload(override: ChannelKeyDisablePolicySettings | null): ChannelKeyDisablePolicyRequest {
  return { override: override ? buildKeyAutoDisableSettingsPayload(override) : null }
}

export function keyAutoDisableRuleLabel(settings: Partial<KeyAutoDisableSettingsResponse> | KeyAutoDisableSettingsRequest | LegacySettings | null | undefined): string {
  if (!settings) return '—'
  const rules = Array.isArray(settings.rules)
    ? settings.rules
    : (isLegacySettings(settings) ? legacyRules(settings) : [])
  if (!rules.length) return settings.enabled === false ? '已关闭自动禁用' : '—'
  const summary = rules.map((rule) => rule.name).join('、')
  return settings.enabled === false ? `已关闭自动禁用（${summary}）` : summary
}

/**
 * 新建规则草稿；id 只用于前端展示与后端计数键。
 * [reservedIds] 是当前列表之外必须避开的 id（渠道编辑器传入全局规则 id）：
 * 同 id 会让渠道规则接管同名全局规则，自动生成的 id 不该制造这种巧合。
 */
export function newKeyDisableRule(
  rules: Array<Pick<KeyDisableRule, 'id'>> = [],
  reservedIds: string[] = [],
): KeyDisableRuleDraft {
  const used = new Set([...rules.map((rule) => rule.id), ...reservedIds])
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
    action: 'TEMPORARY' as const,
    disableMinutes: 30,
    includeTransportFailures: false,
  }
}

/** 规则草稿转编辑字段。 */
export function toKeyDisableRuleDraft(rule: KeyDisableRule): KeyDisableRuleDraft {
  return {
    ...rule,
    statusCodesText: rule.statusCodes.join(','),
    bodyPatternsText: rule.bodyPatterns.join('\n'),
  }
}

/** 编辑字段转提交规则。 */
export function fromKeyDisableRuleDraft(draft: KeyDisableRuleDraft): KeyDisableRule {
  return {
    ...draft,
    disableMinutes: draft.action === 'TEMPORARY' ? draft.disableMinutes : null,
    statusCodes: String(draft.statusCodesText ?? '')
      .split(',')
      .map((code) => code.trim())
      .filter(Boolean)
      .map((code) => integer(code, 100, 599, '状态码')),
    bodyPatterns: String(draft.bodyPatternsText ?? '')
      .split('\n')
      .map((pattern) => pattern.trim())
      .filter(Boolean),
  }
}
