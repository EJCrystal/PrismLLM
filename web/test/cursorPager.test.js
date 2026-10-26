import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createCursorPager } from '../src/utils/cursorPager.js'
import { filterRequestLogs } from '../src/utils/requestLogSearch.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('cursorPager: 游标分页并发安全', () => {
  it('刷新会丢弃刷新前尚未完成的加载更多响应', async () => {
    const stalePage = deferred()
    const calls = []
    let firstPageCalls = 0
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async (options) => {
        calls.push(options)
        if (options.beforeId != null) return stalePage.promise
        firstPageCalls += 1
        return firstPageCalls === 1
          ? [{ id: 10 }, { id: 9 }, { id: 8 }]
          : [{ id: 12 }, { id: 11 }, { id: 10 }]
      },
    })

    await pager.load()
    const more = pager.loadMore()
    assert.equal(pager.loadingMore.value, true)

    const refresh = pager.load()
    stalePage.resolve([{ id: 8 }])
    await Promise.all([more, refresh])

    assert.deepEqual(pager.items.value.map((entry) => entry.id), [12, 11])
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.deepEqual(calls[1], { limit: 3, beforeId: 9 })
  })

  it('正常加载更多时按游标键去重并更新是否还有下一页', async () => {
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async ({ beforeId }) => beforeId == null
        ? [{ id: 4 }, { id: 3 }, { id: 2 }]
        : [{ id: 3 }, { id: 2 }, { id: 1 }],
    })

    await pager.load()
    await pager.loadMore()

    assert.deepEqual(pager.items.value.map((entry) => entry.id), [4, 3, 2])
    assert.equal(pager.hasMore.value, true)
  })

  it('切换每页条数后丢弃旧游标并重新加载第一页', async () => {
    const calls = []
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async (options) => {
        calls.push(options)
        return Array.from({ length: options.limit }, (_, index) => ({ id: options.limit - index }))
      },
    })

    await pager.load()
    await pager.setPageSize(25)

    assert.equal(pager.pageSize.value, 25)
    assert.equal(pager.items.value.length, 25)
    assert.deepEqual(calls.at(-1), { limit: 26 })
    assert.equal('beforeId' in calls.at(-1), false)
  })

  it('已加载多页后切换条数会清空旧页并重置加载更多状态', async () => {
    const calls = []
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async (options) => {
        calls.push(options)
        if (options.beforeId != null) return [{ id: 8 }, { id: 7 }, { id: 6 }]
        if (options.limit === 5) return [{ id: 20 }, { id: 19 }, { id: 18 }, { id: 17 }, { id: 16 }]
        return [{ id: 10 }, { id: 9 }, { id: 8 }]
      },
    })

    await pager.load()
    await pager.loadMore()
    assert.deepEqual(pager.items.value.map((entry) => entry.id), [10, 9, 8, 7])

    await pager.setPageSize(4)

    assert.deepEqual(pager.items.value.map((entry) => entry.id), [20, 19, 18, 17])
    assert.equal(pager.hasMore.value, true)
    assert.equal(pager.loadingMore.value, false)
    assert.deepEqual(calls.at(-1), { limit: 5 })
  })

  it('加载更多未完成时切换条数会丢弃旧游标响应', async () => {
    const stalePage = deferred()
    const calls = []
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async (options) => {
        calls.push(options)
        if (options.beforeId != null) return stalePage.promise
        if (options.limit === 4) return [{ id: 30 }, { id: 29 }, { id: 28 }, { id: 27 }]
        return [{ id: 10 }, { id: 9 }, { id: 8 }]
      },
    })

    await pager.load()
    const staleMore = pager.loadMore()
    assert.equal(pager.loadingMore.value, true)

    const resized = pager.setPageSize(3)
    stalePage.resolve([{ id: 8 }, { id: 7 }, { id: 6 }])
    await Promise.all([staleMore, resized])

    assert.deepEqual(pager.items.value.map((entry) => entry.id), [30, 29, 28])
    assert.deepEqual(calls.at(-1), { limit: 4 })
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(pager.hasMore.value, true)
  })

  it('切换条数后首屏为空会清空记录并关闭加载更多', async () => {
    let request = 0
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async () => {
        request += 1
        return request === 1 ? [{ id: 2 }, { id: 1 }] : []
      },
    })

    await pager.load()
    await pager.setPageSize(100)

    assert.equal(pager.pageSize.value, 100)
    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(await pager.loadMore(), false)
  })

  it('切换条数后的首屏请求失败也不会恢复旧页数据', async () => {
    let request = 0
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async () => {
        request += 1
        if (request === 1) return [{ id: 2 }, { id: 1 }]
        throw new Error('load failed')
      },
    })

    await pager.load()
    await pager.setPageSize(100)

    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(pager.error.value, 'load failed')
  })

  it('快速连续切换条数时只应用最后一次首屏响应', async () => {
    const page25 = deferred()
    const page100 = deferred()
    const calls = []
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async (options) => {
        calls.push(options)
        if (options.limit === 26) return page25.promise
        if (options.limit === 101) return page100.promise
        return Array.from({ length: 51 }, (_, index) => ({ id: 500 - index }))
      },
    })

    await pager.load()
    const switchTo25 = pager.setPageSize(25)
    const switchTo100 = pager.setPageSize(100)

    page100.resolve(Array.from({ length: 101 }, (_, index) => ({ id: 1_000 - index })))
    await switchTo100
    page25.resolve(Array.from({ length: 26 }, (_, index) => ({ id: 2_000 - index })))
    await switchTo25

    assert.equal(pager.pageSize.value, 100)
    assert.equal(pager.items.value.length, 100)
    assert.equal(pager.items.value[0].id, 1_000)
    assert.equal(pager.items.value.at(-1).id, 901)
    assert.equal(pager.hasMore.value, true)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.deepEqual(calls.at(-1), { limit: 101 })
  })

  it('首次加载失败时保持空状态且不会启动加载更多', async () => {
    let calls = 0
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async () => {
        calls += 1
        throw new Error('first page failed')
      },
    })

    assert.equal(await pager.load(), false)

    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(pager.error.value, 'first page failed')
    assert.equal(await pager.loadMore(), false)
    assert.equal(calls, 1)
  })

  it('首次加载返回空结果时关闭加载更多并保持空列表', async () => {
    let calls = 0
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async () => {
        calls += 1
        return []
      },
    })

    assert.equal(await pager.load(), true)

    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(pager.error.value, '')
    assert.equal(await pager.loadMore(), false)
    assert.equal(calls, 1)
  })

  it('筛选变化并快速切换条数时只保留最后条数和最新筛选条件', async () => {
    const page25 = deferred()
    const page100 = deferred()
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async (options) => {
        if (options.limit === 26) return page25.promise
        if (options.limit === 101) return page100.promise
        return Array.from({ length: 51 }, (_, index) => ({ id: 500 - index, statusCode: 200 }))
      },
    })

    await pager.load()
    const switchTo25 = pager.setPageSize(25)
    const switchTo100 = pager.setPageSize(100)

    page100.resolve([
      { id: 1_000, statusCode: 500 },
      ...Array.from({ length: 100 }, (_, index) => ({ id: 999 - index, statusCode: 200 })),
    ])
    await switchTo100
    page25.resolve([{ id: 2_000, statusCode: 404 }])
    await switchTo25

    assert.equal(pager.pageSize.value, 100)
    assert.equal(pager.items.value[0].id, 1_000)
    assert.equal(pager.items.value.length, 100)
    assert.equal(filterRequestLogs(pager.items.value, '500').length, 1)
    assert.equal(filterRequestLogs(pager.items.value, '404').length, 0)
  })

  it('新首屏为空时旧加载更多响应不能恢复记录或游标', async () => {
    const staleMore = deferred()
    let request = 0
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async (options) => {
        request += 1
        if (options.beforeId != null) return staleMore.promise
        if (request === 1) return [{ id: 10 }, { id: 9 }, { id: 8 }]
        return []
      },
    })

    await pager.load()
    const loadingMore = pager.loadMore()
    const resized = pager.setPageSize(4)
    await resized
    staleMore.resolve([{ id: 8 }, { id: 7 }, { id: 6 }])
    await loadingMore

    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
  })

  it('新首屏失败时旧加载更多响应不能清除错误或恢复记录', async () => {
    const staleMore = deferred()
    let request = 0
    const pager = createCursorPager({
      pageSize: 2,
      fetchPage: async (options) => {
        request += 1
        if (options.beforeId != null) return staleMore.promise
        if (request === 1) return [{ id: 10 }, { id: 9 }, { id: 8 }]
        throw new Error('new page failed')
      },
    })

    await pager.load()
    const loadingMore = pager.loadMore()
    const resized = pager.setPageSize(4)
    await resized
    staleMore.resolve([{ id: 8 }, { id: 7 }, { id: 6 }])
    await loadingMore

    assert.deepEqual(pager.items.value, [])
    assert.equal(pager.hasMore.value, false)
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
    assert.equal(pager.error.value, 'new page failed')
  })

  it('旧首屏失败不能覆盖较新的成功响应', async () => {
    const page25 = deferred()
    const page100 = deferred()
    const pager = createCursorPager({
      pageSize: 50,
      fetchPage: async (options) => {
        if (options.limit === 26) return page25.promise
        if (options.limit === 101) return page100.promise
        return Array.from({ length: 51 }, (_, index) => ({ id: 500 - index }))
      },
    })

    await pager.load()
    const switchTo25 = pager.setPageSize(25)
    const switchTo100 = pager.setPageSize(100)
    page100.resolve(Array.from({ length: 101 }, (_, index) => ({ id: 1_000 - index })))
    await switchTo100
    page25.reject(new Error('stale failure'))
    await switchTo25

    assert.equal(pager.pageSize.value, 100)
    assert.equal(pager.items.value.length, 100)
    assert.equal(pager.items.value[0].id, 1_000)
    assert.equal(pager.error.value, '')
    assert.equal(pager.loading.value, false)
    assert.equal(pager.loadingMore.value, false)
  })
})
