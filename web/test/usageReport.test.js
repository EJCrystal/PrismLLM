import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { usageCostEntries, reportRange } from '../src/utils/usageReport.js'

describe('usageReport: 用量报表展示', () => {
  it('费用条目按币种排序并剔除空值，不隐含换算', () => {
    assert.deepEqual(usageCostEntries({ USD: '0.25', CNY: '2' }), [
      { currency: 'CNY', value: '2' },
      { currency: 'USD', value: '0.25' },
    ])
    assert.deepEqual(usageCostEntries({ USD: null }), [])
    assert.deepEqual(usageCostEntries({}), [])
  })

  it('时间范围默认最多 366 天', () => {
    const now = 10_000_000_000
    assert.deepEqual(reportRange(7, now), { from: now - 7 * 86_400_000, to: now })
    assert.deepEqual(reportRange(9999, now), { from: now - 366 * 86_400_000, to: now })
    assert.deepEqual(reportRange(0, now), { from: now - 86_400_000, to: now })
  })
})
