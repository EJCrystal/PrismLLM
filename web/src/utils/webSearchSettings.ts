export type WebSearchBaseUrlSource = 'settings' | 'environment' | 'none'

/**
 * 决定保存时提交的搜索地址。
 * 继承自环境变量的地址在未改动时提交空值，避免把它固化成显式覆盖；
 * 管理台自己保存的地址则原样保留。
 */
export function baseUrlForSave(input: {
  baseUrl: string
  inheritedBaseUrl: string
  baseUrlSource: WebSearchBaseUrlSource | undefined
}): string {
  const trimmed = input.baseUrl.trim()
  if (input.baseUrlSource === 'environment' && trimmed === input.inheritedBaseUrl.trim()) return ''
  return trimmed
}

/** 切换后端时，只要地址仍是打开弹窗时的值，就清空，避免把旧后端的地址留给新后端。 */
export function baseUrlAfterProviderChange(formBaseUrl: string, inheritedBaseUrl: string): string {
  return formBaseUrl === inheritedBaseUrl ? '' : formBaseUrl
}

export function isLoopbackUrl(value: string): boolean {
  try {
    const host = new URL(value).hostname.replace(/^\[|\]$/g, '').toLowerCase()
    return host === 'localhost' || host === '127.0.0.1' || host === '::1'
  } catch {
    return false
  }
}
