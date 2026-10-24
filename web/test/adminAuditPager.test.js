import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createAdminAuditPager } from '../src/utils/adminAuditPager.js'

function deferred() {
  let resolve
  const promise = new Promise((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('adminAuditPager: 审计分页并发安全', () => {
  it('刷新会丢弃刷新前尚未完成的加载更多响应', async () => {
    const stalePage = deferred()
    const calls = []
    let firstPageCalls = 0
    const pager = createAdminAuditPager({
      pageSize: 2,
      fetchPage: async (options) => {
        calls.push(options)
        if (options.beforeId != null) return stalePage.promise
        firstPageCalls += 1
        return firstPageCalls === 1
          ? [{ id: 10 }, { id: 9 }]
          : [{ id: 12 }, { id: 11 }]
      },
    })

    await pager.load()
    const more = pager.loadMore()
    assert.equal(pager.loadingMore.value, true)

    const refresh = pager.load()
    stalePage.resolve([{ id: 8 }])
    await Promise.all([more, refresh])

    assert.deepEqual(pager.logs.value.map((entry) => entry.id), [12, 11])
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.deepEqual(calls[1], { limit: 2, beforeId: 9 })
  })

  it('正常加载更多时按 ID 去重并更新是否还有下一页', async () => {
    const pager = createAdminAuditPager({
      pageSize: 2,
      fetchPage: async ({ beforeId }) => beforeId == null
        ? [{ id: 4 }, { id: 3 }]
        : [{ id: 3 }, { id: 2 }],
    })

    await pager.load()
    await pager.loadMore()

    assert.deepEqual(pager.logs.value.map((entry) => entry.id), [4, 3, 2])
    assert.equal(pager.hasMore.value, true)
  })
})
