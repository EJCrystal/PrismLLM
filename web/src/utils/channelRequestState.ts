import { watch } from 'vue'
import type { Channel, UpstreamRequestOverrides } from '../types/api'
import { normalizeRequestOverrides } from './requestOverrides'

export interface RequestTracker {
  begin: () => number
  invalidate: () => void
  isCurrent: (candidateId: number) => boolean
}

export function createRequestTracker(onInvalidate: () => void = () => {}): RequestTracker {
  let requestId = 0

  return {
    begin() {
      requestId += 1
      return requestId
    },
    invalidate() {
      requestId += 1
      onInvalidate()
    },
    isCurrent(candidateId) {
      return candidateId === requestId
    },
  }
}

export interface ChannelRequestForm {
  baseUrl: string
  credential?: string | null
  authType: string
  protocolAuthTypes?: Record<string, string> | null
  requestOverrides?: UpstreamRequestOverrides | null
}

export function watchChannelRequestInputs(
  form: ChannelRequestForm,
  {
    modelRequests,
    probeRequests,
    onInputsChanged,
    credentialSource = () => form.credential,
    settingsSource = () => undefined,
  }: {
    modelRequests: Pick<RequestTracker, 'invalidate'>
    probeRequests: Pick<RequestTracker, 'invalidate'>
    onInputsChanged: () => void
    credentialSource?: () => unknown
    settingsSource?: () => unknown
  },
) {
  return watch(
    () => [form.baseUrl, credentialSource(), form.authType, JSON.stringify(form.protocolAuthTypes || {}), settingsSource()],
    () => {
      modelRequests.invalidate()
      probeRequests.invalidate()
      onInputsChanged()
    },
    { flush: 'sync' },
  )
}

export function hasCredentialDraft(keys: Array<{ credential?: string | null }> | null | undefined): boolean {
  return (keys || []).some((key) => String(key.credential ?? '').trim() !== '')
}

export function canProbeSavedChannel(
  savedChannel: Channel | null | undefined,
  {
    baseUrl,
    credential,
    authType,
    protocolAuthTypes,
    requestOverrides,
  }: {
    baseUrl: string | null | undefined
    credential: string | null | undefined
    authType: string | null | undefined
    protocolAuthTypes?: Record<string, string> | null
    requestOverrides?: UpstreamRequestOverrides | null
  },
): boolean {
  if (!savedChannel) return false
  if (requestOverrides === null) return false
  return String(baseUrl ?? '').trim() === String(savedChannel.baseUrl ?? '').trim()
    && !String(credential ?? '').trim()
    && authType === (savedChannel.authType || 'bearer')
    && JSON.stringify(protocolAuthTypes || {}) === JSON.stringify(savedChannel.protocolAuthTypes || {})
    && (requestOverrides === undefined || JSON.stringify(normalizeRequestOverrides(requestOverrides))
      === JSON.stringify(normalizeRequestOverrides(savedChannel.requestOverrides)))
}

export function sameProtocolSelection(left: unknown[] | null | undefined, right: unknown[] | null | undefined): boolean {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false
  return left.every((protocol) => right.includes(protocol))
}
