export const MODEL_TEST_DEFAULT_PROMPT = '请只回复 OK'

export const MODEL_TEST_PROTOCOL_LABELS = {
  chat_completions: 'Chat Completions',
  responses: 'Responses',
  messages: 'Anthropic Messages',
}

export const MODEL_TEST_REASONING_OPTIONS = [
  { value: '', label: '默认（不传）' },
  { value: 'minimal', label: '最低' },
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
]

export function normalizeModelTestSettings(settings) {
  return {
    defaultPrompt: String(settings?.defaultPrompt ?? '').trim() || MODEL_TEST_DEFAULT_PROMPT,
    defaultReasoningEffort: String(settings?.defaultReasoningEffort ?? '').trim(),
  }
}

export function supportsReasoningEffort(protocol) {
  return protocol !== 'messages'
}

export function modelTestProtocolLabel(protocol) {
  return MODEL_TEST_PROTOCOL_LABELS[protocol] || protocol
}

export function buildModelTestRequest({ model, protocol, prompt, reasoningEffort }) {
  return {
    model: String(model ?? '').trim(),
    protocol: String(protocol ?? '').trim(),
    prompt: String(prompt ?? '').trim(),
    reasoningEffort: supportsReasoningEffort(protocol)
      ? (String(reasoningEffort ?? '').trim() || null)
      : null,
  }
}

export function modelTestResultTone(result) {
  return result?.success ? 'success' : 'danger'
}

export function modelTestResultTitle(result) {
  if (!result) return ''
  return result.success ? '连通性正常' : '连通性测试失败'
}

export function formatModelTestUsage(usage) {
  if (!usage) return ''
  const parts = []
  if (usage.inputTokens != null) parts.push(`输入 ${usage.inputTokens}`)
  if (usage.outputTokens != null) parts.push(`输出 ${usage.outputTokens}`)
  if (usage.cachedTokens != null) parts.push(`缓存读取 ${usage.cachedTokens}`)
  if (usage.reasoningTokens != null) parts.push(`推理 ${usage.reasoningTokens}`)
  if (usage.totalTokens != null) parts.push(`总计 ${usage.totalTokens}`)
  return parts.join(' · ')
}
