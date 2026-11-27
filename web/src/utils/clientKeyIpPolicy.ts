import type { ClientKeyIpPolicy } from '../types/api'

function parseEntries(value: string): string[] {
  const entries = [...new Set(value.split(/[\s,，;；]+/u).map(entry => entry.trim()).filter(Boolean))]
  if (entries.length > 256) throw new Error('每份 IP 名单最多允许 256 条规则')
  return entries
}

export function buildClientKeyIpPolicy(allowlist: string, denylist: string): ClientKeyIpPolicy {
  return { allowlist: parseEntries(allowlist), denylist: parseEntries(denylist) }
}

export function clientKeyIpPolicySummary(policy?: ClientKeyIpPolicy | null): string {
  const parts: string[] = []
  if (policy?.allowlist.length) parts.push('IP 白名单 ' + policy.allowlist.length)
  if (policy?.denylist.length) parts.push('IP 黑名单 ' + policy.denylist.length)
  return parts.join(' · ')
}
