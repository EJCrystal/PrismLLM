import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildPricePayload, priceFormState } from '../src/utils/priceForm.js'

describe('priceForm: 计费单价的币种与金额', () => {
  it('编辑已有非 USD 单价时保留原币种和金额', () => {
    const form = priceFormState({
      currency: 'CNY',
      inputPricePerMillion: '7.00000000',
      outputPricePerMillion: '14.00000000',
      cachedInputPricePerMillion: '1.00000000',
    })
    const payload = buildPricePayload(form, 'channel-a', 'model-a')

    assert.equal(form.currency, 'CNY')
    assert.equal(payload.currency, 'CNY')
    assert.equal(payload.inputPricePerMillion, '7.00000000')
    assert.equal(payload.outputPricePerMillion, '14.00000000')
    assert.equal(payload.cachedInputPricePerMillion, '1.00000000')
  })

  it('新建单价默认使用 USD', () => {
    assert.equal(priceFormState(null).currency, 'USD')
    const payload = buildPricePayload(priceFormState(null), 'channel-a', ' model-a ')
    assert.equal(payload.currency, 'USD')
    assert.equal(payload.upstreamModel, 'model-a')
    assert.equal(payload.cachedInputPricePerMillion, null)
  })

  it('编辑时清空缓存单价会提交 null，而不是空字符串', () => {
    const payload = buildPricePayload(
      { currency: 'USD', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputPricePerMillion: ' ' },
      'channel-a',
      'model-a',
    )
    assert.equal(payload.cachedInputPricePerMillion, null)
  })

  it('缺少币种时安全回退到 USD', () => {
    const payload = buildPricePayload(
      { currency: '', inputPricePerMillion: '1', outputPricePerMillion: '2' },
      'channel-a',
      'model-a',
    )
    assert.equal(payload.currency, 'USD')
  })
})
