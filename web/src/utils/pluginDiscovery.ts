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
  for (const item of available) {
    const key = pluginContributionVersionKey(item)
    if (!invalid.has(key) || signal.aborted) continue
    try {
      const live = await api.getPlugin(item.pluginId, { signal })
      if (live.id === item.pluginId && live.admissionEnabled && live.activeVersion === item.activeVersion &&
        live.activeUi?.entries.some(entry => entry.id === item.entry.id && entry.action.id === item.entry.action.id &&
          entry.placement === item.entry.placement && item.entry.contexts.every(context => entry.contexts.includes(context)))) {
        recovered.push(key)
      }
    } catch (cause) {
      if (signal.aborted) throw cause
      error ||= cause instanceof Error ? cause.message : String(cause)
    }
  }
  return { recovered, error }
}
