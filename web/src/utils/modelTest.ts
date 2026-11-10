import type { ChannelModelTestResult, ChannelModelTestUsage, ModelTestSettings } from '../types/api'

export const MODEL_TEST_DEFAULT_PROMPT = '请只回复 OK'

export const MODEL_TEST_PROTOCOL_LABELS: Record<string, string> = {
  chat_completions: 'Chat Completions',
  responses: 'Responses',
  messages: 'Anthropic Messages',
  gemini: 'Gemini',
}

export function normalizeModelTestSettings(settings: ModelTestSettings | null | undefined): { defaultPrompt: string } {
  return {
    defaultPrompt: String(settings?.defaultPrompt ?? '').trim() || MODEL_TEST_DEFAULT_PROMPT,
  }
}

export function modelTestProtocolLabel(protocol: string): string {
  return MODEL_TEST_PROTOCOL_LABELS[protocol] || protocol
}

/** 渠道与模型组合在检测目标下拉中的唯一 key。 */
export function modelTestTargetKey(channelId: string, model: string): string {
  return `${channelId}\u0000${model}`
}

export interface ModelTestRequestInput {
  model?: string | null
  protocol?: string | null
  prompt?: string | null
  keyId?: string | null
}

export interface ModelTestRequest {
  model: string
  protocol: string
  keyId: string | null
  prompt: string
}

export function buildModelTestRequest({ model, protocol, prompt, keyId }: ModelTestRequestInput): ModelTestRequest {
  return {
    model: String(model ?? '').trim(),
    protocol: String(protocol ?? '').trim(),
    keyId: String(keyId ?? '').trim() || null,
    prompt: String(prompt ?? '').trim(),
  }
}

export function modelTestResultTone(result: Pick<ChannelModelTestResult, 'success'> | null | undefined): 'success' | 'danger' {
  return result?.success ? 'success' : 'danger'
}

export function modelTestResultTitle(result: Pick<ChannelModelTestResult, 'success'> | null | undefined): string {
  if (!result) return ''
  return result.success ? '连通性正常' : '连通性测试失败'
}

export function formatModelTestUsage(usage: ChannelModelTestUsage | null | undefined): string {
  if (!usage) return ''
  const parts = []
  if (usage.inputTokens != null) parts.push(`输入 ${usage.inputTokens}`)
  if (usage.outputTokens != null) parts.push(`输出 ${usage.outputTokens}`)
  if (usage.cachedTokens != null) parts.push(`缓存读取 ${usage.cachedTokens}`)
  if (usage.reasoningTokens != null) parts.push(`推理 ${usage.reasoningTokens}`)
  if (usage.totalTokens != null) parts.push(`总计 ${usage.totalTokens}`)
  return parts.join(' · ')
}
