import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { reactive, ref } from 'vue'
import {
  canProbeSavedChannel,
  createRequestTracker,
  sameProtocolSelection,
  watchChannelRequestInputs,
} from '../src/utils/channelRequestState'

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

  it('请求覆盖变更会丢弃正在返回的模型列表和协议探测', () => {
    const revision = ref(0)
    const form = reactive({ baseUrl: 'https://example.com/v1', authType: 'bearer' })
    const models = createRequestTracker()
    const probes = createRequestTracker()
    const modelId = models.begin()
    const probeId = probes.begin()
    const stop = watchChannelRequestInputs(form, {
      modelRequests: models, probeRequests: probes, onInputsChanged: () => {}, settingsSource: () => revision.value,
    })
    revision.value += 1
    assert.equal(models.isCurrent(modelId), false)
    assert.equal(probes.isCurrent(probeId), false)
    stop()
  })

  it('修改或清空 UA 后需保存再探测，缺省新配置时仍兼容旧调用', () => {
    const saved = { baseUrl: 'https://example.com/v1', authType: 'bearer', requestOverrides: { userAgent: 'saved-UA' } }
    const inputs = { baseUrl: saved.baseUrl, authType: saved.authType, credential: '' }
    assert.equal(canProbeSavedChannel(saved, inputs), true)
    assert.equal(canProbeSavedChannel(saved, { ...inputs, requestOverrides: { userAgent: 'saved-UA' } }), true)
    assert.equal(canProbeSavedChannel(saved, { ...inputs, requestOverrides: { userAgent: '' } }), false)
    assert.equal(canProbeSavedChannel(saved, { ...inputs, requestOverrides: null }), false)
  })

  it('协议快照按集合比较，任何增删都使模型发现结果过期', () => {
    assert.equal(sameProtocolSelection(['chat_completions', 'responses'], ['responses', 'chat_completions']), true)
    assert.equal(sameProtocolSelection(['chat_completions'], []), false)
    assert.equal(sameProtocolSelection(['chat_completions'], ['chat_completions', 'gemini']), false)
    assert.equal(sameProtocolSelection(['gemini'], ['responses']), false)
  })
})
