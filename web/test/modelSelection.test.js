import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  clearedVersionHistoryState,
  modelSelectionAction,
  resolveModelSelectionTarget,
} from '../src/utils/modelSelection.js'

describe('modelSelection: 渠道模型切换', () => {
  const channelId = 'channel-a'
  const prices = [
    {
      id: 'price-a',
      channelId,
      upstreamModel: 'model-a',
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
    },
  ]

  it('切换到已有价格时加载该模型自己的价格', () => {
    const target = resolveModelSelectionTarget(channelId, ' model-a ', prices)
    assert.equal(target.modelId, 'model-a')
    assert.equal(target.price?.id, 'price-a')
    assert.equal(target.draft.currency, 'CNY')
    assert.equal(target.draft.inputPricePerMillion, '7')
  })

  it('切换到未定价模型时使用空白草稿，不继承上一模型金额', () => {
    const target = resolveModelSelectionTarget(channelId, 'model-b', prices)
    assert.equal(target.price, null)
    assert.equal(target.draft.currency, 'USD')
    assert.equal(target.draft.inputPricePerMillion, '')
    assert.equal(target.draft.outputPricePerMillion, '')
  })

  it('切换模型时清空旧版本历史并让加载请求失效', () => {
    assert.deepEqual(clearedVersionHistoryState(), {
      invalidateRequest: true,
      showVersions: false,
      loadingVersions: false,
      versionHistory: [],
    })
  })

  it('同一模型的价格列表暂时刷新时保留正在编辑的草稿', () => {
    assert.equal(modelSelectionAction(null, { id: 'price-a' }, false), 'preserve')
    assert.equal(modelSelectionAction(null, { id: 'price-a' }, true), 'clear')
    assert.equal(modelSelectionAction({ id: 'price-b' }, { id: 'price-a' }, true), 'load')
  })
})
