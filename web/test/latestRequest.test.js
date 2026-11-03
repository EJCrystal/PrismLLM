import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createLatestRequestGuard, refreshUntilCurrent } from '../src/utils/latestRequest'

describe('latestRequest: 丢弃过期报表响应', () => {
  it('保存后的刷新失效时等待下一次有效列表，不能提前解析保存的渠道', async () => {
    const guard = createLatestRequestGuard()
    let releaseFirst
    let releaseSecond
    let reads = 0
    let channels = []
    let finished = false
    const refreshing = refreshUntilCurrent(async () => {
      const result = await guard.run(() => new Promise((resolve) => {
        if (++reads === 1) releaseFirst = resolve
        else releaseSecond = resolve
      }))
      if (result.isLatest) channels = result.value
      return result.isLatest
    }, () => false).then(() => { finished = true })
    // Another channel update invalidates the in-flight post-save read.
    guard.invalidate()
    releaseFirst([{ id: 'old' }])
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(reads, 2)
    assert.equal(finished, false)
    assert.deepEqual(channels, [])
    releaseSecond([{ id: 'new-channel' }])
    await refreshing
    assert.deepEqual(channels, [{ id: 'new-channel' }])
    assert.equal(finished, true)
  })

  it('离开渠道页后不再重试失效的刷新', async () => {
    let disposed = false
    let release
    let reads = 0
    const refreshing = refreshUntilCurrent(() => {
      reads++
      return new Promise((resolve) => { release = resolve })
    }, () => disposed)
    disposed = true
    release(false)
    await refreshing
    assert.equal(reads, 1)
  })

  it('只让最后开始的请求提交结果', async () => {
    const guard = createLatestRequestGuard()
    const first = guard.run(async () => 'slow')
    const second = guard.run(async () => 'fast')

    assert.deepEqual(await first, { value: 'slow', request: 1, isLatest: false })
    assert.deepEqual(await second, { value: 'fast', request: 2, isLatest: true })
  })

  it('失败请求保留是否为最新请求的标记', async () => {
    const guard = createLatestRequestGuard()
    const failure = await guard.run(async () => { throw new Error('offline') }).catch((error) => error)
    assert.equal(failure.isLatest, true)
  })


  it('并发加载期间保持 hasActive，最后一个请求结束后释放', async () => {
    const guard = createLatestRequestGuard()
    let release
    const pending = guard.run(() => new Promise((resolve) => { release = resolve }))
    assert.equal(guard.hasActive(), true)
    release('done')
    await pending
    assert.equal(guard.hasActive(), false)
  })

  it('先完成的旧请求不会清掉仍在进行的新请求标记', async () => {
    const guard = createLatestRequestGuard()
    let releaseOld
    let releaseNew
    const old = guard.run(() => new Promise((resolve) => { releaseOld = resolve }))
    const fresh = guard.run(() => new Promise((resolve) => { releaseNew = resolve }))
    releaseOld('old')
    await old
    assert.equal(guard.hasActive(), true)
    releaseNew('new')
    await fresh
    assert.equal(guard.hasActive(), false)
  })

  it('invalidate 后尚未完成的请求不再被认定为最新', async () => {
    const guard = createLatestRequestGuard()
    const pending = guard.run(async () => 'slow')
    guard.invalidate()
    const result = await pending
    assert.equal(result.isLatest, false)
    assert.equal(guard.isLatest(result.request), false)
  })
})
