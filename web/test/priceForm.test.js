import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  applyPriceToForm,
  buildPricePayload,
  emptyPriceDraft,
  hasPriceDraft,
  priceFormState,
  resetPriceForm,
} from '../src/utils/priceForm.js'

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

  it('从已有 CNY 单价切换到未定价模型时重置为 USD 草稿', () => {
    const existing = priceFormState({
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
    })
    assert.equal(existing.currency, 'CNY')

    const draft = emptyPriceDraft()
    assert.equal(draft.currency, 'USD')
    assert.equal(draft.inputPricePerMillion, '')
    assert.equal(draft.outputPricePerMillion, '')
    assert.equal(draft.cachedInputPricePerMillion, '')
  })

  it('检测用户是否已填写单价草稿', () => {
    assert.equal(hasPriceDraft(emptyPriceDraft()), false)
    assert.equal(hasPriceDraft({ inputPricePerMillion: '0.1' }), true)
    assert.equal(hasPriceDraft({ outputPricePerMillion: '  ' }), false)
    assert.equal(hasPriceDraft({ cachedInputPricePerMillion: '0.05' }), true)
    assert.equal(hasPriceDraft(null), false)
  })

  it('点击已配置单价时回填渠道、模型、币种和金额', () => {
    const form = { channelId: 'c1', upstreamModel: '', ...emptyPriceDraft() }
    applyPriceToForm(form, {
      channelId: 'c2',
      upstreamModel: 'deepseek-v4.1-flash',
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
      cachedInputPricePerMillion: '1',
    })
    assert.equal(form.channelId, 'c2')
    assert.equal(form.upstreamModel, 'deepseek-v4.1-flash')
    assert.equal(form.currency, 'CNY')
    assert.equal(form.inputPricePerMillion, '7')
    assert.equal(form.outputPricePerMillion, '14')
    assert.equal(form.cachedInputPricePerMillion, '1')
  })

  it('新增单价时清空模型与金额并恢复 USD', () => {
    const form = {
      channelId: 'c1',
      upstreamModel: 'deepseek-v4.1-flash',
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
      cachedInputPricePerMillion: '1',
    }
    resetPriceForm(form)
    assert.equal(form.upstreamModel, '')
    assert.equal(form.currency, 'USD')
    assert.equal(form.inputPricePerMillion, '')
    assert.equal(form.outputPricePerMillion, '')
    assert.equal(form.cachedInputPricePerMillion, '')
  })
})
