export const MODEL_TEST_DEFAULT_PROMPT = '请只回复 OK'

export const MODEL_TEST_PROTOCOL_LABELS = {
  chat_completions: 'Chat Completions',
  responses: 'Responses',
  messages: 'Anthropic Messages',
  gemini: 'Gemini',
}

export function normalizeModelTestSettings(settings) {
  return {
    defaultPrompt: String(settings?.defaultPrompt ?? '').trim() || MODEL_TEST_DEFAULT_PROMPT,
  }
}

export function modelTestProtocolLabel(protocol) {
  return MODEL_TEST_PROTOCOL_LABELS[protocol] || protocol
}

export function buildModelTestRequest({ model, protocol, prompt, keyId }) {
  return {
    model: String(model ?? '').trim(),
    protocol: String(protocol ?? '').trim(),
    keyId: String(keyId ?? '').trim() || null,
    prompt: String(prompt ?? '').trim(),
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
