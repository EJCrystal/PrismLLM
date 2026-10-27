import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildChannelPriceDeleteMessage,
  clonePriceDraftForm,
  collectDirtyPriceDrafts,
  createPriceDraft,
  priceDraftHasChanges,
  priceDraftKey,
  priceDraftSnapshot,
} from '../src/utils/priceDraftState.js'
import { emptyPriceDraft } from '../src/utils/priceForm.js'

function draft(channelId, modelId, patch = {}, price = null, baseline = null) {
  const baselineForm = emptyPriceDraft()
  return createPriceDraft({
    channelId,
    modelId,
    form: { ...emptyPriceDraft(), ...patch },
    price,
    baseline: baseline ?? priceDraftSnapshot(baselineForm),
  })
}

describe('priceDraftState: 多模型价格草稿', () => {
  it('为渠道和模型生成稳定 key，并去除首尾空白', () => {
    assert.equal(priceDraftKey(' channel-a ', ' model-a '), priceDraftKey('channel-a', 'model-a'))
    assert.notEqual(priceDraftKey('channel-a', 'model-a'), priceDraftKey('channel-a', 'model-b'))
  })

  it('未修改的草稿不会被视为待保存', () => {
    const item = draft('channel-a', 'model-a')
    assert.equal(priceDraftHasChanges(item), false)
    assert.equal(priceDraftSnapshot(item.form), item.baseline)
  })

  it('编辑金额后只标记当前模型草稿为待保存', () => {
    const item = draft('channel-a', 'model-a', {
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
    })
    assert.equal(priceDraftHasChanges(item), true)
  })

  it('收集多个草稿时只返回有修改的模型，并按模型排序', () => {
    const drafts = new Map()
    const modelA = draft('channel-a', 'model-a', {
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
    })
    const modelC = draft('channel-a', 'model-c', {
      inputPricePerMillion: '3',
      outputPricePerMillion: '6',
    })
    const modelB = draft('channel-a', 'model-b', {
      inputPricePerMillion: '2',
      outputPricePerMillion: '4',
    })
    const unchanged = draft('channel-a', 'model-d')
    drafts.set(modelA.key, modelA)
    drafts.set(modelC.key, modelC)
    drafts.set(modelB.key, modelB)
    drafts.set(unchanged.key, unchanged)

    const dirty = collectDirtyPriceDrafts(drafts)
    assert.deepEqual(dirty.map((item) => item.modelId), ['model-a', 'model-b', 'model-c'])
    assert.deepEqual(
      dirty.map((item) => [item.form.inputPricePerMillion, item.form.outputPricePerMillion]),
      [['1', '2'], ['2', '4'], ['3', '6']],
    )
  })

  it('当前表单优先于已暂存的同名模型草稿', () => {
    const stored = draft('channel-a', 'model-a', {
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
    })
    const current = draft('channel-a', 'model-a', {
      inputPricePerMillion: '9',
      outputPricePerMillion: '18',
    })

    const dirty = collectDirtyPriceDrafts(new Map([[stored.key, stored]]), current)
    assert.equal(dirty.length, 1)
    assert.equal(dirty[0].form.inputPricePerMillion, '9')
  })

  it('草稿复制不会共享阶梯数组，避免切换模型后互相污染', () => {
    const source = {
      ...emptyPriceDraft(),
      pricingMode: 'tiered',
      priceTiers: [{ upToTokens: '100', inputPricePerMillion: '1', outputPricePerMillion: '2' }],
    }
    const copied = clonePriceDraftForm(source)
    copied.priceTiers[0].inputPricePerMillion = '99'

    assert.equal(source.priceTiers[0].inputPricePerMillion, '1')
  })

  it('保存后更新基线即可清除待保存状态', () => {
    const item = draft('channel-a', 'model-a', {
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
    })
    item.baseline = priceDraftSnapshot(item.form)

    assert.equal(priceDraftHasChanges(item), false)
  })

  it('删除确认提示说明回退目标，并提醒未保存编辑会丢弃', () => {
    const noDefault = buildChannelPriceDeleteMessage({
      modelId: 'model-a',
      hasUnsavedDraft: true,
    })
    assert.match(noDefault, /删除后将恢复为未定价/)
    assert.match(noDefault, /尚未保存的编辑也会一并丢弃/)

    const withDefault = buildChannelPriceDeleteMessage({
      modelId: 'model-b',
      defaultPrice: {
        currency: 'USD',
        inputPricePerMillion: '0.5',
        outputPricePerMillion: '1.5',
        pricingMode: 'token',
      },
    })
    assert.match(withDefault, /删除后将恢复为继承模型默认价/)
    assert.doesNotMatch(withDefault, /尚未保存的编辑/)
  })
})
