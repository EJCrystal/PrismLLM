import type { ChannelKeyEditorDraft, KeyModelOption } from '../types/channelEditor'
import { watch } from 'vue'

/** Watch by stable local ID: additions, removals and form hydration never clear other keys. */
export function watchKeyCredentials(
  keys: () => ChannelKeyEditorDraft[],
  shouldInvalidate: () => boolean,
  onChanged: () => void,
) {
  return watch(
    () => keys().map(key => ({ localId: key.localId, credential: key.credential.trim() })),
    (_current, previous) => {
      if (!shouldInvalidate()) return
      const before = new Map(previous.map(key => [key.localId, key.credential]))
      let changed = false
      for (const key of keys()) {
        if (before.has(key.localId) && before.get(key.localId) !== key.credential.trim()) {
          invalidateKeyModels(key)
          changed = true
        }
      }
      if (changed) onChanged()
    },
    { flush: 'sync' },
  )
}

/** Model selections and manual entries belong to one upstream credential. */
export function invalidateKeyModels(key: ChannelKeyEditorDraft): void {
  key.availableModels = []
  key.models = []
  key.manualModels = []
  key.manualModelsText = ''
  key.manualModelsError = ''
  key.modelsLoading = false
  key.modelsError = ''
  key.modelsSourceChanged = true
  key.modelsRequestId += 1
}

export function applyDiscoveredKeyModels(key: ChannelKeyEditorDraft, models: KeyModelOption[]): void {
  key.availableModels = models
  if (key.modelsSourceChanged) {
    const selectedManual = key.manualModels.filter(id => key.models.includes(id))
    key.models = [...new Set([...models.map(model => model.id), ...selectedManual])]
  } else {
    const available = new Set([...models.map(model => model.id), ...key.manualModels])
    key.models = key.models.filter(id => available.has(id))
  }
  key.modelsSourceChanged = false
}

export function filterKeyModels(models: KeyModelOption[], query: string): KeyModelOption[] {
  const search = query.trim().toLocaleLowerCase()
  return models.filter(model => model.id.toLocaleLowerCase().includes(search))
}
