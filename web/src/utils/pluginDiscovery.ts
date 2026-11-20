import { api } from '../api'
import type { PluginUiContribution } from '../types/plugin'

export function pluginContributionVersionKey(item: { pluginId: string; activeVersion: string; entry: { id: string } }): string {
  return `${item.pluginId}:${item.activeVersion}:${item.entry.id}`
}

export function currentInvalidPluginContributions(available: PluginUiContribution[], invalid: Set<string>): Set<string> {
  const current = new Set(available.map(pluginContributionVersionKey))
  return new Set([...invalid].filter(key => current.has(key)))
}

export async function recheckPluginContributions(available: PluginUiContribution[], invalid: Set<string>, signal: AbortSignal) {
  const recovered: string[] = []
  let error = ''
  const groups = new Map<string, PluginUiContribution[]>()
  for (const item of available) {
    if (!invalid.has(pluginContributionVersionKey(item))) continue
    const entries = groups.get(item.pluginId) ?? []
    entries.push(item)
    groups.set(item.pluginId, entries)
  }
  for (const [pluginId, entries] of groups) {
    if (signal.aborted) break
    try {
      const live = await api.getPlugin(pluginId, { signal })
      for (const item of entries) {
        if (live.id === item.pluginId && live.admissionEnabled && live.activeVersion === item.activeVersion &&
          live.activeUi?.entries.some(entry => entry.id === item.entry.id && entry.action.id === item.entry.action.id &&
            entry.placement === item.entry.placement && item.entry.contexts.every(context => entry.contexts.includes(context)))) {
          recovered.push(pluginContributionVersionKey(item))
        }
      }
    } catch (cause) {
      if (signal.aborted) throw cause
      error ||= cause instanceof Error ? cause.message : String(cause)
    }
  }
  return { recovered, error }
}
