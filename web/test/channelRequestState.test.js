import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { reactive, ref } from 'vue'
import { canProbeSavedChannel, createRequestTracker, watchChannelRequestInputs } from '../src/utils/channelRequestState.js'

describe('channel request state', () => {
  it('clears loading and invalidates model discovery and probing when auth changes', () => {
    const modelsLoading = ref(true)
    const probing = ref(true)
    const form = reactive({ baseUrl: 'https://api.example.com/v1', credential: '', authType: 'bearer' })
    const requests = createRequestTracker(() => {
      modelsLoading.value = false
    })
    const probes = createRequestTracker(() => {
      probing.value = false
    })
    const modelRequestId = requests.begin()
    const probeRequestId = probes.begin()
    const stopWatching = watchChannelRequestInputs(form, {
      modelRequests: requests,
      probeRequests: probes,
      onInputsChanged: () => {},
    })

    form.authType = 'anthropic'

    assert.equal(modelsLoading.value, false)
    assert.equal(probing.value, false)
    assert.equal(requests.isCurrent(modelRequestId), false)
    assert.equal(probes.isCurrent(probeRequestId), false)
    stopWatching()
  })

  it('allows protocol probing only with the saved channel authentication settings', () => {
    const savedChannel = {
      baseUrl: 'https://api.example.com/v1',
      authType: 'anthropic',
    }

    assert.equal(canProbeSavedChannel(savedChannel, {
      baseUrl: ' https://api.example.com/v1 ',
      credential: '',
      authType: 'anthropic',
    }), true)
    assert.equal(canProbeSavedChannel(savedChannel, {
      baseUrl: savedChannel.baseUrl,
      credential: '',
      authType: 'bearer',
    }), false)
    assert.equal(canProbeSavedChannel(savedChannel, {
      baseUrl: savedChannel.baseUrl,
      credential: 'replacement-secret',
      authType: 'anthropic',
    }), false)
  })
})
