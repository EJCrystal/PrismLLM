import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { findModelPrice } from '../src/utils/modelPrice.js'

describe('modelPrice: 按渠道和上游模型查找单价', () => {
  const prices = [
    { id: 'price-a', channelId: 'channel-a', upstreamModel: 'model-a' },
    { id: 'price-b', channelId: 'channel-b', upstreamModel: 'model-a' },
  ]

  it('只匹配同一渠道的同一上游模型', () => {
    assert.equal(findModelPrice(prices, 'channel-a', 'model-a'), prices[0])
  })

  it('渠道或模型不匹配时返回 null', () => {
    assert.equal(findModelPrice(prices, 'channel-c', 'model-a'), null)
    assert.equal(findModelPrice(prices, 'channel-a', 'model-b'), null)
  })

  it('缺少目标或无效列表时返回 null', () => {
    assert.equal(findModelPrice(prices, '', 'model-a'), null)
    assert.equal(findModelPrice(null, 'channel-a', 'model-a'), null)
  })
})
