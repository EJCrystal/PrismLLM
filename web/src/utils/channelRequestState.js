import { watch } from 'vue'
import { normalizeRequestOverrides } from './requestOverrides.js'

export function createRequestTracker(onInvalidate = () => {}) {
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

export function watchChannelRequestInputs(
  form,
  { modelRequests, probeRequests, onInputsChanged, credentialSource = () => form.credential, settingsSource = () => undefined },
) {
  return watch(
    () => [form.baseUrl, credentialSource(), form.authType, settingsSource()],
    () => {
      modelRequests.invalidate()
      probeRequests.invalidate()
      onInputsChanged()
    },
    { flush: 'sync' },
  )
}

export function canProbeSavedChannel(savedChannel, { baseUrl, credential, authType, requestOverrides }) {
  if (!savedChannel) return false
  if (requestOverrides === null) return false
  return String(baseUrl ?? '').trim() === String(savedChannel.baseUrl ?? '').trim()
    && !String(credential ?? '').trim()
    && authType === (savedChannel.authType || 'bearer')
    && (requestOverrides === undefined || JSON.stringify(normalizeRequestOverrides(requestOverrides))
      === JSON.stringify(normalizeRequestOverrides(savedChannel.requestOverrides)))
}

export function sameProtocolSelection(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false
  return left.every((protocol) => right.includes(protocol))
}
