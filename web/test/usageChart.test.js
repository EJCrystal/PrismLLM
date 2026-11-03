import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { usageChartIndex, usageChartPoint, usageChartScale } from '../src/utils/usageChart'
import { buildTrendPoints } from '../src/utils/usageReport'

describe('usage chart interaction and axes', () => {
  it('includes all data in readable ticks ending at zero', () => {
    for (const value of [1, 463, 41_200_000, .00042]) {
      const scale = usageChartScale(value)
      assert.ok(scale.max >= value)
      assert.equal(scale.ticks[0], scale.max)
      assert.equal(scale.ticks.at(-1), 0)
      assert.ok(scale.ticks.every((tick, i) => i === 0 || tick < scale.ticks[i - 1]))
    }
    assert.equal(usageChartScale(0).max, 1)
    assert.equal(usageChartScale(NaN).max, 1)
  })
  it('maps pointer coordinates to the nearest real bucket with edge clamping', () => {
    assert.equal(usageChartIndex(-100, 100, 640, 8), 0)
    assert.equal(usageChartIndex(1000, 100, 640, 8), 7)
    assert.equal(usageChartIndex(420, 100, 640, 7), 3)
    assert.equal(usageChartIndex(420, 100, 320, 1), 0)
    assert.equal(usageChartIndex(420, 100, 320, 0), -1)
  })
  it('aligns crosshair points with rendered lines, including single buckets', () => {
    assert.deepEqual(usageChartPoint(0, 1, 10, 10), { x: 320, y: 8 })
    assert.deepEqual(usageChartPoint(1, 2, 0, 10), { x: 632, y: 142 })
    assert.equal(buildTrendPoints([{ value: .0004 }], row => row.value, 640, 150, 8, .0004), '320.0,8.0')
  })
})
