import { afterEach, describe, it, mock } from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { useAutoRefresh } from '../src/composables/useAutoRefresh'

const originalDocument = globalThis.document

afterEach(() => {
  mock.timers.reset()
  globalThis.document = originalDocument
})

function installDocument() {
  const listeners = new Map()
  const document = {
    visibilityState: 'visible',
    addEventListener(type, handler) { listeners.set(type, handler) },
    removeEventListener(type) { listeners.delete(type) },
  }
  globalThis.document = document
  return {
    listeners,
    show() {
      document.visibilityState = 'visible'
      listeners.get('visibilitychange')?.()
    },
    hide() {
      document.visibilityState = 'hidden'
      listeners.get('visibilitychange')?.()
    },
  }
}

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

  it('does not listen for visibility changes unless refreshOnVisible is set', async () => {
    const env = installDocument()
    const interval = ref(5_000)
    let refreshes = 0
    const scope = effectScope()
    scope.run(() => useAutoRefresh(interval, () => { refreshes++ }, () => false))
    await nextTick()

    assert.equal(env.listeners.size, 0)
    env.show()
    assert.equal(refreshes, 0)
    scope.stop()
  })

  it('refreshes when the page becomes visible and restarts the interval', async () => {
    mock.timers.enable({ apis: ['setInterval'] })
    const env = installDocument()
    const interval = ref(30_000)
    let refreshes = 0
    let busy = false
    const scope = effectScope()
    scope.run(() => useAutoRefresh(interval, () => { refreshes++ }, () => busy, { refreshOnVisible: true }))
    await nextTick()

    env.hide()
    assert.equal(refreshes, 0, 'hidden pages do not trigger an immediate refresh')
    env.show()
    assert.equal(refreshes, 1)

    mock.timers.tick(29_999)
    assert.equal(refreshes, 1, 'the interval restarts from the foreground refresh')
    mock.timers.tick(1)
    assert.equal(refreshes, 2)

    busy = true
    env.show()
    assert.equal(refreshes, 2, 'busy views skip the foreground refresh')
    busy = false
    env.show()
    assert.equal(refreshes, 3)

    scope.stop()
    assert.equal(env.listeners.size, 0)
    env.show()
    assert.equal(refreshes, 3, 'leaving the view removes the listener')
  })

  it('re-registers the visibility listener when the interval changes', async () => {
    mock.timers.enable({ apis: ['setInterval'] })
    const env = installDocument()
    const interval = ref(5_000)
    let refreshes = 0
    const scope = effectScope()
    scope.run(() => useAutoRefresh(interval, () => { refreshes++ }, () => false, { refreshOnVisible: true }))
    await nextTick()
    const firstListener = env.listeners.get('visibilitychange')

    interval.value = 10_000
    await nextTick()
    assert.notEqual(env.listeners.get('visibilitychange'), firstListener)
    assert.equal(env.listeners.size, 1)
    env.show()
    assert.equal(refreshes, 1)
    scope.stop()
  })
})
