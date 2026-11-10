import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { reactive, ref } from 'vue'
import {
  canProbeSavedChannel,
  createRequestTracker,
  hasCredentialDraft,
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

  it('多个密钥的凭据都未修改时不产生凭据草稿', () => {
    assert.equal(hasCredentialDraft([{ credential: '' }, { credential: '' }, { credential: '' }]), false)
    assert.equal(hasCredentialDraft([{ credential: '   ' }, { credential: null }]), false)
    assert.equal(hasCredentialDraft([]), false)
    assert.equal(hasCredentialDraft([{ credential: '' }, { credential: 'sk-new' }]), true)
  })

  it('表单回填期间不清理已保存的模型授权', () => {
    const form = reactive({ baseUrl: '', authType: 'bearer' })
    let hydrating = true
    let invalidated = 0
    const models = createRequestTracker()
    const probes = createRequestTracker()
    const stop = watchChannelRequestInputs(form, {
      modelRequests: models,
      probeRequests: probes,
      shouldInvalidate: () => !hydrating,
      onInputsChanged: () => { invalidated += 1 },
    })

    form.baseUrl = 'https://api.example.com/v1'
    assert.equal(invalidated, 0, '打开编辑表单回填地址时不应清空模型授权')

    hydrating = false
    form.baseUrl = 'https://api.example.com/v2'
    assert.equal(invalidated, 1, '用户修改上游地址后仍应清空模型授权')
    stop()
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
