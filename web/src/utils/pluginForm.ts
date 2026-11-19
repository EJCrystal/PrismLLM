import type { PluginField, PluginValue, PluginValues } from '../types/api'

export const PLUGIN_STATE_CHANGED = 'gateway:plugins-changed'

export function pluginFieldDefaults(fields: PluginField[], configuration: PluginValues = {}, promptAlias = false): PluginValues {
  return Object.fromEntries(fields.map((field) => {
    const sourceKey = promptAlias && field.key === 'prompt' ? 'defaultPrompt' : field.key
    const configured = configuration[sourceKey]
    const empty = field.kind === 'boolean' ? false : !field.required && (field.kind === 'number' || field.kind === 'select') ? null : ''
    return [field.key, Object.hasOwn(configuration, sourceKey) ? configured : field.defaultValue ?? empty]
  }))
}

export function pluginFormError(fields: PluginField[], values: PluginValues): string {
  for (const field of fields) {
    const value: PluginValue | undefined = values[field.key]
    if (value == null || value === '') {
      if (field.required) return `请填写${field.label}`
      continue
    }
    if (field.kind === 'boolean') {
      if (typeof value !== 'boolean') return `${field.label}必须是开关值`
    } else if (field.kind === 'number') {
      if (typeof value !== 'number' || !Number.isFinite(value)) return `${field.label}必须是数字`
      if (field.min != null && value < field.min) return `${field.label}不能小于 ${field.min}`
      if (field.max != null && value > field.max) return `${field.label}不能大于 ${field.max}`
    } else {
      if (typeof value !== 'string') return `${field.label}必须是文本`
      if (field.required && !value.trim()) return `请填写${field.label}`
      if (field.maxLength != null && value.trim().length > field.maxLength) return `${field.label}不能超过 ${field.maxLength} 个字符`
      if (field.options.length && !field.options.some((option) => option.value === value)) return `${field.label}的选项无效`
    }
  }
  return ''
}

export function pluginStateLabel(status: string): string {
  return ({ active: '已生效', 'pending-restart': '待重启', disabled: '已禁用', uninstalled: '未安装', error: '加载失败' } as Record<string, string>)[status] ?? '不可用'
}

export function pluginPermissionLabel(permission: string): string {
  return ({ 'diagnostics.channel.probe': '使用已保存的渠道凭据进行诊断', 'diagnostics.oauth-account.probe': '使用订阅账号原生凭据进行诊断' } as Record<string, string>)[permission] ?? permission
}
