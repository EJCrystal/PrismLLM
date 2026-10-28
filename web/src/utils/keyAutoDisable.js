export const DEFAULT_KEY_AUTO_DISABLE_SETTINGS = Object.freeze({
  enabled: true,
  authFailureThreshold: 3,
  failureThreshold: 5,
  failureWindowMinutes: 5,
})

/** 同时校验读取结果和保存输入，避免加载失败后覆盖线上规则。 */
export function normalizeKeyAutoDisableSettings(value) {
  if (typeof value?.enabled !== 'boolean') throw new Error('自动禁用设置格式无效，请重新加载')
  const fields = [
    ['authFailureThreshold', 100, '连续 401/403 次数', ''],
    ['failureThreshold', 1000, '窗口内失败次数', ''],
    ['failureWindowMinutes', 1440, '失败统计窗口', ' 分钟'],
  ]
  const result = { enabled: value.enabled }
  for (const [field, max, label, unit] of fields) {
    const number = value[field]
    if (!Number.isInteger(number) || number < 1 || number > max) {
      throw new Error(`${label}必须是 1..${max}${unit}之间的整数`)
    }
    result[field] = number
  }
  return result
}

export function buildKeyAutoDisableSettingsPayload(form) {
  for (const field of ['authFailureThreshold', 'failureThreshold', 'failureWindowMinutes']) {
    if (form[field] === '' || form[field] === null || form[field] === undefined) {
      throw new Error('自动禁用规则的次数和窗口不能为空')
    }
  }
  return normalizeKeyAutoDisableSettings({
    enabled: form.enabled,
    authFailureThreshold: Number(form.authFailureThreshold),
    failureThreshold: Number(form.failureThreshold),
    failureWindowMinutes: Number(form.failureWindowMinutes),
  })
}

export function keyAutoDisableRuleLabel(settings) {
  if (!settings) return '—'
  const rule = `连续 ${settings.authFailureThreshold} 次 401/403，或 ${settings.failureWindowMinutes} 分钟内失败 ${settings.failureThreshold} 次`
  if (settings.enabled) return rule
  const hasThresholds = ['authFailureThreshold', 'failureThreshold', 'failureWindowMinutes']
    .every((field) => Number.isInteger(settings[field]) && settings[field] > 0)
  return hasThresholds ? `已关闭自动禁用（${rule}）` : '已关闭自动禁用'
}
