import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createRetentionReportState } from '../src/utils/retentionReportState.js'

describe('retentionReportState: 报表刷新与保留开关协调', () => {
  it('刷新期间没有切换开关时应用最新保留策略', () => {
    const state = createRetentionReportState()
    const load = state.beginLoad()
    assert.equal(state.shouldApplyRetention(load), true)
  })

  it('切换开关后，刷新开始前拿到的旧保留策略被丢弃', () => {
    const state = createRetentionReportState()
    const load = state.beginLoad()
    state.beginToggle()
    assert.equal(state.shouldApplyRetention(load), false)
  })

  it('切换后新发起的刷新可以应用保留策略', () => {
    const state = createRetentionReportState()
    state.beginToggle()
    const load = state.beginLoad()
    assert.equal(state.shouldApplyRetention(load), true)
  })

  it('多次切换只让最后一次之后的加载生效', () => {
    const state = createRetentionReportState()
    const stale = state.beginLoad()
    state.beginToggle()
    state.beginToggle()
    assert.equal(state.shouldApplyRetention(stale), false)
    assert.equal(state.shouldApplyRetention(state.beginLoad()), true)
  })

  it('切换进行期间启动的加载在切换结束后失效', () => {
    const state = createRetentionReportState()
    state.beginToggle()
    const duringToggle = state.beginLoad()
    state.endToggle()
    assert.equal(state.shouldApplyRetention(duringToggle), false)
    assert.equal(state.shouldApplyRetention(state.beginLoad()), true)
  })
})
