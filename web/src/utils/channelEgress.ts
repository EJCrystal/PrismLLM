const loopbackHosts = new Set(['127.0.0.1', 'localhost', '::1'])
const hostPattern = /^[a-z0-9][a-z0-9._-]*$/
export const MAX_CHANNEL_PROXY_ALLOWED_HOSTS = 64

/** Java/URL 的 IPv6 主机可能带方括号；统一成小写、去方括号和后缀点。 */
export function normalizeChannelProxyHost(host: string): string {
  return host.trim().replace(/^\[/, '').replace(/\]$/, '').toLowerCase().replace(/\.$/, '')
}

function normalizeEntry(entry: string): string {
  return entry.trim().toLowerCase().replace(/\.$/, '')
}

export function isValidChannelProxyHostEntry(entry: string): boolean {
  const host = normalizeEntry(entry)
  if (host.startsWith('*.')) {
    const suffix = host.slice(2)
    return suffix.length <= 253 && suffix.includes('.') && hostPattern.test(suffix)
  }
  return host.length <= 253 && hostPattern.test(host)
}

/** 解析管理台输入的主机列表，支持换行、逗号、分号和空格分隔。 */
export function parseChannelProxyHostEntries(text: string): string[] {
  const entries = [...new Set(text.split(/[\s,，;；]+/u).map(normalizeEntry).filter(Boolean))]
  if (entries.length > MAX_CHANNEL_PROXY_ALLOWED_HOSTS) {
    throw new Error(`最多允许 ${MAX_CHANNEL_PROXY_ALLOWED_HOSTS} 个主机`)
  }
  const invalid = entries.find((entry) => !isValidChannelProxyHostEntry(entry))
  if (invalid) throw new Error(`「${invalid}」不是有效主机；只接受主机名、IPv4 或 *.域名 通配`)
  return entries
}

export function channelProxyHostAllowed(host: string, allowedHosts: string[]): boolean {
  const normalized = normalizeChannelProxyHost(host)
  if (!normalized) return false
  if (loopbackHosts.has(normalized)) return true
  return allowedHosts.some((entry) => {
    const value = normalizeEntry(entry)
    if (value.startsWith('*.')) {
      const suffix = value.slice(2)
      return suffix.length > 0 && normalized.length > suffix.length + 1 && normalized.endsWith(`.${suffix}`)
    }
    return normalized === value
  })
}

/**
 * 按字面解析代理地址中的主机，和后端 Java `URI.host` 保持一致。
 * 不能用 `new URL().hostname`：WHATWG 会把 `10.0.0.01` 规范化成 `10.0.0.1`，
 * 甚至把 `010.0.0.1` 当成八进制；一键放行会保存成另一个主机，重试仍被拒绝。
 */
export function channelProxyHostFromUrl(url: string): string {
  const value = url.trim()
  try {
    new URL(value) // 只验证地址格式，hostname 的结果不能用于白名单比较。
  } catch {
    return ''
  }
  const schemeEnd = value.indexOf('://')
  if (schemeEnd < 0) return ''
  const authority = value.slice(schemeEnd + 3).split(/[/?#]/)[0]
  if (authority.includes('@')) return ''
  const host = authority.startsWith('[')
    ? authority.slice(0, authority.indexOf(']') + 1)
    : authority.split(':')[0]
  return normalizeChannelProxyHost(host)
}

/** 从待保存的代理地址中找出尚未放行的非回环主机。 */
export function blockedChannelProxyHosts(urls: string[], allowedHosts: string[]): string[] {
  return [...new Set(
    urls
      .map(channelProxyHostFromUrl)
      .filter((host) => host && !channelProxyHostAllowed(host, allowedHosts)),
  )]
}
