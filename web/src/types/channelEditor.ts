import type { ChannelKeyState } from './api'
import type { ChannelKeyRateLimitDraft } from '../utils/channelKeyRateLimit'

export interface KeyModelOption {
  id: string
  ownedBy?: string | null
  authorized?: boolean
}

export interface ChannelKeyEditorDraft {
  localId: string
  id: string | null
  name: string
  credential: string
  state: ChannelKeyState
  originalState: ChannelKeyState
  priority: number | string | null
  stateVersion: number | null
  autoDisableReason: string | null
  autoDisableUntil: number | null
  rateLimit: ChannelKeyRateLimitDraft & Record<string, unknown>
  models: string[]
  availableModels: KeyModelOption[]
  manualModels: string[]
  showManualModels: boolean
  manualModelsText: string
  manualModelsError: string
  modelsLoading: boolean
  modelsError: string
  modelsRequestId: number
}
