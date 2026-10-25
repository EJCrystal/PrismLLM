import { watch } from 'vue'

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

export function watchChannelRequestInputs(form, { modelRequests, probeRequests, onInputsChanged }) {
  return watch(
    () => [form.baseUrl, form.credential, form.authType],
    () => {
      modelRequests.invalidate()
      probeRequests.invalidate()
      onInputsChanged()
    },
    { flush: 'sync' },
  )
}

export function canProbeSavedChannel(savedChannel, { baseUrl, credential, authType }) {
  if (!savedChannel) return false
  return String(baseUrl ?? '').trim() === String(savedChannel.baseUrl ?? '').trim()
    && !String(credential ?? '').trim()
    && authType === (savedChannel.authType || 'bearer')
}
