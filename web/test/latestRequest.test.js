import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createLatestRequestGuard } from '../src/utils/latestRequest.js'

describe('latestRequest: 丢弃过期报表响应', () => {
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
