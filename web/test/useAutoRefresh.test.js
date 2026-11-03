import { afterEach, describe, it, mock } from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { useAutoRefresh } from '../src/composables/useAutoRefresh'

afterEach(() => mock.timers.reset())

describe('useAutoRefresh', () => {
  it('defaults off and skips ticks while busy', async () => {
    mock.timers.enable({ apis: ['setInterval'] })
    const interval = ref(0)
    const busy = ref(false)
    let refreshes = 0
    const scope = effectScope()
    scope.run(() => useAutoRefresh(interval, () => { refreshes++ }, () => busy.value))

    mock.timers.tick(10_000)
    assert.equal(refreshes, 0)

    interval.value = 5_000
    await nextTick()
    mock.timers.tick(5_000)
    assert.equal(refreshes, 1)

    busy.value = true
    mock.timers.tick(5_000)
    assert.equal(refreshes, 1)

    busy.value = false
    interval.value = 10_000
    await nextTick()
    mock.timers.tick(5_000)
    assert.equal(refreshes, 1, 'the previous timer is cleared when the interval changes')
    mock.timers.tick(5_000)
    assert.equal(refreshes, 2)

    interval.value = 30_000
    await nextTick()
    mock.timers.tick(29_999)
    assert.equal(refreshes, 2)
    mock.timers.tick(1)
    assert.equal(refreshes, 3)

    interval.value = 0
    await nextTick()
    mock.timers.tick(20_000)
    assert.equal(refreshes, 3, 'turning auto refresh off clears the timer')

    interval.value = 5_000
    await nextTick()
    scope.stop()
    mock.timers.tick(10_000)
    assert.equal(refreshes, 3, 'leaving the view clears the timer')
  })
})
