import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createConversionRequestGuard } from '../src/utils/currencyConversion'

describe('currencyConversion: 丢弃过期的币种换算结果', () => {
  it('切换币种后选择了另一条价格，旧请求不再可应用', () => {
    const requests = createConversionRequestGuard()
    const pending = requests.start()

    requests.invalidate()

    assert.equal(requests.isCurrent(pending), false)
  })

  it('未发生选择变化时允许当前请求应用结果', () => {
    const requests = createConversionRequestGuard()
    const pending = requests.start()

    assert.equal(requests.isCurrent(pending), true)
  })

  it('新请求替代旧请求，只允许最新结果生效', () => {
    const requests = createConversionRequestGuard()
    const first = requests.start()
    const latest = requests.start()

    assert.equal(requests.isCurrent(first), false)
    assert.equal(requests.isCurrent(latest), true)
  })
})
