import { api } from '../api'
import type { PluginUiContribution } from '../types/plugin'

const RECHECK_TIMEOUT_MS = 5_000
const RECHECK_CONCURRENCY = 4

export function pluginContributionVersionKey(item: { pluginId: string; activeVersion: string; entry: { id: string } }): string {
  return `${item.pluginId}:${item.activeVersion}:${item.entry.id}`
}

export function currentInvalidPluginContributions(available: PluginUiContribution[], invalid: Set<string>): Set<string> {
  const current = new Set(available.map(pluginContributionVersionKey))
  return new Set([...invalid].filter(key => current.has(key)))
}

export async function recheckPluginContributions(
  available: PluginUiContribution[], invalid: Set<string>, signal: AbortSignal,
  options: { timeoutMs?: number; concurrency?: number } = {},
) {
  const timeoutMs = options.timeoutMs ?? RECHECK_TIMEOUT_MS
  const concurrency = options.concurrency ?? RECHECK_CONCURRENCY
  const groups = new Map<string, PluginUiContribution[]>()
  for (const item of available) {
    if (!invalid.has(pluginContributionVersionKey(item))) continue
    const entries = groups.get(item.pluginId) ?? []
    entries.push(item)
    groups.set(item.pluginId, entries)
  }
  const work = [...groups.entries()]
  const recovered = Array.from({ length: work.length }, () => [] as string[])
  const errors = Array.from({ length: work.length }, () => '')
  let next = 0

  const check = async (index: number, pluginId: string, entries: PluginUiContribution[]) => {
    const controller = new AbortController()
    let timeout: ReturnType<typeof setTimeout> | undefined
    let abortParent: (() => void) | undefined
    try {
      const timedOut = new Promise<never>((_, reject) => {
        timeout = setTimeout(() => {
          controller.abort()
          reject(new Error('插件状态复核超时'))
        }, timeoutMs)
      })
      const aborted = new Promise<never>((_, reject) => {
        abortParent = () => {
          controller.abort(signal.reason)
          reject(signal.reason ?? new DOMException('The operation was aborted', 'AbortError'))
        }
        if (signal.aborted) abortParent()
        else signal.addEventListener('abort', abortParent, { once: true })
      })
      const live = await Promise.race([api.getPlugin(pluginId, { signal: controller.signal }), timedOut, aborted])
      recovered[index] = entries.filter(item => live.id === item.pluginId && live.admissionEnabled && live.activeVersion === item.activeVersion &&
        live.activeUi?.entries.some(entry => entry.id === item.entry.id && entry.action.id === item.entry.action.id &&
          entry.placement === item.entry.placement && item.entry.contexts.every(context => entry.contexts.includes(context))))
        .map(pluginContributionVersionKey)
    } catch (cause) {
      if (signal.aborted) throw cause
      errors[index] = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (timeout) clearTimeout(timeout)
      if (abortParent) signal.removeEventListener('abort', abortParent)
    }
  }

  const worker = async () => {
    while (!signal.aborted) {
      const index = next++
      if (index >= work.length) return
      const [pluginId, entries] = work[index]
      await check(index, pluginId, entries)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, work.length) }, worker))
  if (signal.aborted) throw signal.reason ?? new DOMException('The operation was aborted', 'AbortError')
  return { recovered: recovered.flat(), error: errors.find(Boolean) ?? '' }
}
