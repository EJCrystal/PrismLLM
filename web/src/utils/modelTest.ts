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

export interface ModelTestErrorView {
  /** 一句话说明失败原因，替代直白的状态码。 */
  title: string
  /** 上游原始报错，保留用于排查。 */
  detail: string | null
  /** 可以直接照做的下一步。 */
  hint: string | null
}

/**
 * 把上游报错翻译成用户能处理的说明。例如
 * "Requested entity was not found." 实际表示账号没有该模型的调用权限，
 * 只显示原文会让人误以为网关或网络出了问题。
 */
export function modelTestErrorView(
  result: Pick<ChannelModelTestResult, 'statusCode' | 'errorMessage'> | null | undefined,
): ModelTestErrorView | null {
  const message = String(result?.errorMessage ?? '').trim()
  const status = result?.statusCode ?? null
  // 成功响应没有错误正文：2xx 不能生成「上游返回 HTTP 200」之类的错误视图。
  if (!message && (status == null || (status >= 200 && status < 300))) return null
  const detail = message || null
  if (status === 404) {
    return {
      title: '该账号无法调用这个模型',
      detail,
      hint: '上游没有找到这个模型实体，通常是账号套餐不包含它，或目录同步自其他账号。请重新同步模型目录后再测试，或换一个模型。',
    }
  }
  if (status === 401) {
    return {
      title: '账号凭据已失效',
      detail,
      hint: 'access token 可能已过期且续期失败，请刷新 token 或重新授权后再试。',
    }
  }
  if (status === 403) {
    return {
      title: '账号无权调用这个模型',
      detail,
      hint: '确认当前套餐是否包含该模型；如果刚切换过套餐，请重新同步模型目录。',
    }
  }
  if (status === 429) {
    return {
      title: '上游限流',
      detail,
      hint: '稍后重试；如果持续出现，检查账号额度与冷却状态。',
    }
  }
  if (status != null && status >= 500) {
    return { title: '上游暂时不可用', detail, hint: '稍后重试。' }
  }
  if (message.startsWith('测试超时')) {
    return { title: '测试超时', detail: null, hint: '上游响应过慢，稍后重试；持续超时请检查账号代理与网络。' }
  }
  if (message.includes('未获取到 Antigravity 项目')) {
    return {
      title: '账号还没有 Antigravity 项目',
      detail,
      hint: '先用同一个 Google 账号登录一次 Antigravity 应用完成开通，再重新授权并测试。',
    }
  }
  if (message.startsWith('无法连接上游')) {
    return { title: '无法连接上游', detail, hint: '检查账号代理与网络连通性。' }
  }
  return { title: message || `上游返回 HTTP ${status}`, detail: null, hint: null }
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
