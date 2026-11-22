import type { ProtocolId, SystemPromptRule, SystemPromptScope } from '../types/api'

export const systemPromptProtocols: { value: ProtocolId; label: string }[] = [
  { value: 'messages', label: 'Messages' },
  { value: 'chat_completions', label: 'Chat Completions' },
  { value: 'responses', label: 'Responses' },
  { value: 'gemini', label: 'Gemini' },
]

export const systemPromptScopes: { value: SystemPromptScope; label: string }[] = [
  { value: 'global', label: '全局' },
  { value: 'protocols', label: '指定出口协议' },
  { value: 'channels', label: '指定渠道' },
  { value: 'channel_protocols', label: '指定渠道与出口协议' },
]

export function requiresPromptProtocols(scope: SystemPromptScope): boolean {
  return scope === 'protocols' || scope === 'channel_protocols'
}

export function requiresPromptChannels(scope: SystemPromptScope): boolean {
  return scope === 'channels' || scope === 'channel_protocols'
}

export function createSystemPromptRule(id?: string): SystemPromptRule {
  const ruleId = id ?? globalThis.crypto?.randomUUID?.() ?? 'prompt-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
  return { id: ruleId, name: '', enabled: true, scope: 'global', protocols: [], channelIds: [], priority: 0, action: 'replace', prompt: '' }
}

export function changeSystemPromptScope(rule: SystemPromptRule): void {
  if (!requiresPromptProtocols(rule.scope)) rule.protocols = []
  if (!requiresPromptChannels(rule.scope)) rule.channelIds = []
}

export function systemPromptRuleError(rule: SystemPromptRule, channelIds: Set<string>): string | null {
  if (!rule.name.trim() || rule.name.length > 100) return '规则名称必须为 1 至 100 个字符'
  if (!Number.isInteger(rule.priority) || rule.priority < -2147483648 || rule.priority > 2147483647) return '优先级必须为有效整数'
  if (rule.action === 'replace' && !rule.prompt.trim()) return '替换规则的系统提示词不能为空'
  if (requiresPromptProtocols(rule.scope) && !rule.protocols.length) return '请至少选择一种出口协议'
  if (requiresPromptChannels(rule.scope) && !rule.channelIds.length) return '请至少选择一个渠道'
  if (rule.channelIds.some(id => !channelIds.has(id))) return '规则包含已删除的渠道，请移除后重新选择'
  return null
}
