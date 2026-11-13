import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { JSDOM } from 'jsdom'

describe('request log hierarchy', () => {
  let server
  let component
  let api
  const fixture = (changes = {}) => ({
    id: 1, requestId: 'req-001-1234567890', traceId: 'trace-001', createdAt: 1794555000000,
    alias: 'deepseek-v4.1', channelId: 'channel-001', channelName: '主渠道', channelKeyName: '生产密钥',
    clientKeyId: 'client-001', clientKeyName: '测试调用方', clientIp: '127.0.0.1', clientUserAgent: 'PrismLLM Test',
    upstreamModel: 'deepseek-v4.1-flash', protocol: 'chat_completions', upstreamProtocol: 'chat_completions', stream: true,
    statusCode: 200, errorCode: null, upstreamErrorCode: null, errorMessage: null, failureStage: null,
    usageKnown: true, inputTokens: 1308, outputTokens: 56, totalTokens: 1364, reasoningTokens: null, cachedTokens: null,
    totalMs: 7800, firstTokenMs: 780, costAmount: '0.0002298', costCurrency: 'USD',
    pricingMode: 'token', inputPricePerMillion: '0.15', outputPricePerMillion: '0.6', priceTiers: [],
    upstreamAttempts: 1, attempts: [], ...changes,
  })

  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/LogsView.vue')).default
    api = (await server.ssrLoadModule('/src/api.ts')).api
  })
  after(async () => { await server?.close() })

  async function render(records, selected = null, storedColumns, inspectState) {
    let componentState
    const wrapper = { ...component, setup(props, context) {
      const previousStorage = globalThis.localStorage
      if (storedColumns !== undefined) globalThis.localStorage = { getItem: key => key.endsWith('.columns') ? JSON.stringify(storedColumns) : null }
      let state
      try { state = component.setup(props, context) }
      finally {
        if (previousStorage === undefined) delete globalThis.localStorage
        else globalThis.localStorage = previousStorage
      }
      state.logs.value = records
      state.loading.value = false
      state.selected.value = selected
      componentState = state
      return state
    } }
    const context = {}
    const html = await renderToString(createSSRApp(wrapper), context)
    if (inspectState) await inspectState(componentState)
    return new JSDOM(html + (context.teleports?.body || '')).window.document
  }

  it('renews rolling ranges on every filter query and keeps custom ranges fixed', async (context) => {
    let now = 1_800_000_000_000
    context.mock.method(Date, 'now', () => now)
    const requests = []
    const optionsRequests = []
    context.mock.method(api, 'listRequestLogs', async options => { requests.push({ ...options }); return [] })
    context.mock.method(api, 'listRequestLogFilterOptions', async options => {
      optionsRequests.push({ ...options })
      return { channels: [], models: [], statuses: [] }
    })
    await render([], null, undefined, async state => {
      const originalEnd = state.appliedFilters.value.to
      now += 7_200_000
      state.query.value = 'latest'
      await state.applyFilters()
      assert.equal(requests.at(-1).to, now)
      assert.equal(requests.at(-1).from, now - 86_400_000)
      assert.equal(requests.at(-1).beforeId, undefined)
      assert.notEqual(requests.at(-1).to, originalEnd)
      assert.equal(optionsRequests.at(-1).to, now)
      state.timePreset.value = 'custom'
      state.appliedFilters.value = { from: originalEnd - 1000, to: originalEnd }
      now += 7_200_000
      await state.applyFilters()
      assert.equal(requests.at(-1).from, originalEnd - 1000)
      assert.equal(requests.at(-1).to, originalEnd)
    })
  })

  for (const refreshMethod of ['refresh', 'autoRefresh']) {
    it('keeps the previous range and pagination when ' + refreshMethod + ' fails', async (context) => {
      let now = 1_800_000_000_000
      context.mock.method(Date, 'now', () => now)
      const requests = []
      context.mock.method(api, 'listRequestLogs', async options => {
        requests.push({ ...options })
        if (!options.beforeId) throw new Error('refresh failed')
        return []
      })
      const optionsMock = context.mock.method(api, 'listRequestLogFilterOptions', async () => ({ channels: [], models: [], statuses: [] }))
      const previousRecords = [fixture({ id: 10 })]
      await render(previousRecords, null, undefined, async state => {
        const previousFilters = state.appliedFilters.value
        state.hasMore.value = true
        now += 7_200_000
        await state[refreshMethod]()
        assert.equal(requests[0].to, now)
        assert.equal(state.appliedFilters.value, previousFilters)
        assert.equal(state.logs.value, previousRecords)
        assert.equal(state.hasMore.value, true)
        assert.equal(state.error.value, 'refresh failed')
        assert.equal(optionsMock.mock.callCount(), 0)
        await state.loadMore()
        assert.equal(requests[1].beforeId, 10)
        assert.equal(requests[1].from, previousFilters.from)
        assert.equal(requests[1].to, previousFilters.to)
      })
    })
  }

  it('refreshes options for the same rolling range as a successful automatic refresh', async (context) => {
    let now = 1_800_000_000_000
    context.mock.method(Date, 'now', () => now)
    const requests = []
    context.mock.method(api, 'listRequestLogs', async options => { requests.push({ ...options }); return [fixture({ id: 2 })] })
    const options = { channels: [{ value: 'new-channel', label: '新渠道' }], models: [], statuses: [] }
    const optionsRequests = []
    context.mock.method(api, 'listRequestLogFilterOptions', async filters => { optionsRequests.push({ ...filters }); return options })
    await render([fixture()], null, undefined, async state => {
      now += 60_000
      await state.autoRefresh()
      assert.equal(requests[0].to, now)
      assert.equal(optionsRequests[0].from, requests[0].from)
      assert.equal(optionsRequests[0].to, requests[0].to)
      assert.deepEqual(state.filterOptions.value, options)
      assert.equal(state.logs.value[0].id, 2)
      assert.equal(state.autoRefreshing.value, false)
    })
  })

  it('does not restore stale filters when a failed refresh is superseded by a new query', async (context) => {
    let now = 1_800_000_000_000
    context.mock.method(Date, 'now', () => now)
    let rejectRefresh
    context.mock.method(api, 'listRequestLogs', options => options.q === 'new-query'
      ? Promise.resolve([fixture({ id: 2 })])
      : new Promise((resolve, reject) => { rejectRefresh = reject }))
    context.mock.method(api, 'listRequestLogFilterOptions', async () => ({ channels: [], models: [], statuses: [] }))
    await render([fixture()], null, undefined, async state => {
      now += 60_000
      const pendingRefresh = state.autoRefresh()
      now += 60_000
      state.query.value = 'new-query'
      await state.applyFilters()
      rejectRefresh(new Error('old refresh failed'))
      await pendingRefresh
      assert.equal(state.appliedFilters.value.q, 'new-query')
      assert.equal(state.appliedFilters.value.to, now)
      assert.equal(state.logs.value[0].id, 2)
      assert.equal(state.error.value, '')
    })
  })

  it('shows total tokens and total latency first without empty optional metrics', async () => {
    const document = await render([fixture()])
    const row = document.querySelector('.logs-table tbody tr')
    assert.equal(row.querySelector('.log-model-name').textContent, 'deepseek-v4.1')
    assert.equal(row.querySelector('.log-model-meta .reasoning-badge'), null)
    assert.equal(row.querySelector('.log-channel-heading .channel-key-chip span').textContent, '生产密钥')
    assert.equal(row.querySelector('.usage-tokens b').textContent, '1,364')
    assert.match(row.querySelector('.usage-detail').textContent, /入1,308.*出56/)
    assert.equal(row.querySelector('.usage-extras'), null)
    assert.ok(row.querySelector('.duration-cell').firstElementChild.classList.contains('is-total'))
    assert.ok(row.querySelector('.time-cell').firstElementChild.classList.contains('time-clock'))
    assert.ok(row.querySelector('.request-id-cell button'))
  })

  it('keeps unknown, no-request and zero distinct and only shows positive optional metrics', async () => {
    const document = await render([
      fixture({ id: 1, requestId: 'unknown', usageKnown: false }),
      fixture({ id: 2, requestId: 'no-request', usageKnown: false, channelId: null, upstreamAttempts: 0 }),
      fixture({ id: 3, requestId: 'zero', inputTokens: 0, outputTokens: 0, totalTokens: 0, reasoningTokens: 0, cachedTokens: 0 }),
      fixture({ id: 4, requestId: 'positive', reasoningTokens: 100, cachedTokens: 654 }),
    ])
    const rows = document.querySelectorAll('.logs-table tbody tr')
    assert.equal(rows[0].querySelector('.usage-state-label').textContent, '未知')
    assert.equal(rows[1].querySelector('.usage-state-label').textContent, '无请求')
    assert.equal(rows[2].querySelector('.usage-tokens b').textContent, '0')
    assert.equal(rows[2].querySelector('.usage-extras'), null)
    assert.match(rows[3].querySelector('.usage-extras').textContent, /推理100.*缓存50%/)
  })

  it('keeps optional columns hidden by default while preserving explicit preferences', async () => {
    const defaults = await render([fixture()], null, {})
    assert.equal(defaults.querySelector('.logs-table .log-col-client-ip'), null)
    assert.equal(defaults.querySelector('.logs-table .log-col-client-ua'), null)
    const saved = await render([fixture()], null, { clientIp: true, model: false })
    assert.ok(saved.querySelector('.logs-table .log-col-client-ip'))
    assert.equal(saved.querySelector('.logs-table .log-col-model'), null)
  })

  it('highlights failures independently of HTTP 200 and does not count recovered requests as failures', async () => {
    const document = await render([
      fixture({ requestId: 'server', statusCode: 502 }),
      fixture({ id: 2, requestId: 'limit', statusCode: 429, errorCode: 'rate_limit' }),
      fixture({ id: 3, requestId: 'stream', errorCode: 'stream_interrupted' }),
      fixture({ id: 4, requestId: 'recovered', upstreamAttempts: 2 }),
    ])
    const rows = document.querySelectorAll('.logs-table tbody tr')
    assert.ok(rows[0].classList.contains('log-row-danger'))
    assert.ok(rows[1].classList.contains('log-row-warning'))
    assert.ok(rows[2].classList.contains('log-row-danger'))
    assert.match(rows[2].querySelector('.status-head').textContent, /200.*请求失败/)
    assert.ok(rows[3].classList.contains('log-row-recovered'))
    assert.match(rows[3].textContent, /重试成功.*尝试 2 次/)
    assert.equal(document.querySelectorAll('.logs-summary dd')[1].textContent, '3')
  })

  it('keeps each summary field once and removes duplicated pricing summaries', async () => {
    const selected = fixture()
    const document = await render([selected], selected)
    const detail = document.querySelector('[role="dialog"]')
    const labels = [...detail.querySelectorAll('.detail-summary dt, .detail-item > span')].map(element => element.textContent.trim())
    for (const label of ['请求模型', '渠道', '总耗时', '费用']) assert.equal(labels.filter(value => value === label).length, 1, label)
    assert.ok(!labels.includes('渠道名称'))
    assert.equal(detail.querySelectorAll('.detail-identifiers .detail-item').length, 2)
    assert.equal(detail.querySelector('.detail-price-snapshot .detail-block-head > span').textContent, '计费单价快照')
    assert.match(detail.querySelector('.detail-price-snapshot .detail-block-head').textContent, /按 Token 计费/)
    assert.equal(detail.querySelectorAll('.detail-price-row').length, 4)
    assert.match(detail.querySelector('.detail-price-list').textContent, /沿用输入价/)
  })

  it('restores detail keyboard focus after navigation removes the active control', async (context) => {
    const dom = new JSDOM('<div tabindex="-1" id="detail"></div>')
    const previousDocument = globalThis.document
    globalThis.document = dom.window.document
    context.after(() => {
      if (previousDocument === undefined) delete globalThis.document
      else globalThis.document = previousDocument
      dom.window.close()
    })
    const first = fixture()
    const second = fixture({ id: 2, requestId: 'next-request' })
    await render([first, second], first, undefined, async state => {
      const detail = dom.window.document.querySelector('#detail')
      state.detailDialog.value = detail
      await state.navigateDetail(1)
      assert.equal(state.selected.value.requestId, 'next-request')
      assert.equal(dom.window.document.activeElement, detail)
    })
  })

  it('renders mobile-first summaries and separate server result filters', async () => {
    const document = await render([fixture()])
    const card = document.querySelector('.log-card')
    assert.match(card.querySelector('.log-card-heading').textContent, /deepseek-v4.1.*200.*成功/)
    assert.match(card.querySelector('.log-card-metrics').textContent, /总耗时.*Token.*1,364.*费用/)
    assert.match(document.querySelector('#logs-filter-scope').textContent, /服务端查询/)
    assert.ok(document.querySelector('.logs-filter-controls [aria-label^="按请求结果筛选"]'))
  })

  it('hides inapplicable error fields and collapses only single successful attempts', async () => {
    const selected = fixture({ attempts: [{ index: 1, statusCode: 200, elapsedMs: 50, committed: true }] })
    const document = await render([selected], selected)
    const detail = document.querySelector('[role="dialog"]')
    assert.equal(detail.querySelector('.log-attempt-disclosure').hasAttribute('open'), false)
    const labels = [...detail.querySelectorAll('.detail-item > span')].map(element => element.textContent.trim())
    assert.ok(!labels.includes('失败阶段'))
    assert.ok(!labels.includes('错误码'))
    const failed = { ...selected, errorCode: 'stream_interrupted' }
    const second = await render([failed], failed)
    assert.equal(second.querySelector('.log-attempt-disclosure').hasAttribute('open'), true)
  })

  it('groups the overview, route, and usage into individually named cards', async () => {
    const selected = fixture()
    const document = await render([selected], selected)
    const cards = [...document.querySelectorAll('section.detail-card')]
    assert.deepEqual(cards.map(card => card.querySelector('h3').textContent), ['请求概况', '路由与渠道', '耗时与用量'])
    for (const card of cards) {
      assert.equal(card.getAttribute('aria-labelledby'), card.querySelector('h3').id)
      assert.ok(card.querySelector('.detail-grid'))
    }
    assert.equal(document.querySelector('.detail-section-title'), null)
  })

  it('omits absent optional detail fields but keeps explicitly recorded zeros', async () => {
    const missing = fixture({ reasoningEffort: ' none ', firstTokenMs: null, clientIp: null, clientUserAgent: null, traceId: null, clientKeyId: null })
    const absent = await render([missing], missing)
    const labels = [...absent.querySelectorAll('.detail-item > span')].map(element => element.textContent.trim())
    for (const label of ['推理 Token', '推理强度', '缓存读取', '缓存写入', '首 token', '来源 IP', 'User-Agent']) assert.ok(!labels.includes(label), label)
    assert.equal(absent.querySelectorAll('.detail-meta-item').length, 1)
    const zero = fixture({ inputTokens: 0, outputTokens: 0, totalTokens: 0, reasoningTokens: 0, cachedTokens: 0, cachedWriteTokens: 0, firstTokenMs: 0, reasoningEffort: 'high' })
    const present = await render([zero], zero)
    const fields = [...present.querySelectorAll('.detail-item')]
    for (const label of ['总 Token', '推理 Token', '缓存读取', '缓存写入']) {
      assert.equal(fields.find(field => field.firstElementChild.textContent === label).querySelector('strong').textContent.trim(), '0', label)
    }
    assert.match(present.querySelector('#request-usage-title').parentElement.textContent, /high/)
    assert.doesNotMatch(present.querySelector('#request-usage-title').parentElement.textContent, /NaN|Infinity|—/)
  })

  it('states unknown or unrequested usage once without exposing stale optional metrics', async () => {
    for (const [changes, label] of [[{}, '未知'], [{ upstreamAttempts: 0, channelId: null, upstreamModel: null }, '无请求']]) {
      const selected = fixture({ usageKnown: false, reasoningTokens: 50, cachedTokens: 100, cachedWriteTokens: 20, ...changes })
      const document = await render([selected], selected)
      const usage = document.querySelector('#request-usage-title').parentElement
      assert.match(usage.textContent, new RegExp(label))
      const labels = [...usage.querySelectorAll('.detail-item > span')].map(element => element.textContent)
      for (const optional of ['推理 Token', '缓存读取', '缓存写入', '输出速率']) assert.ok(!labels.includes(optional), optional)
      assert.equal(labels.filter(value => value === 'Token 用量').length, 1)
    }
  })

  it('shows an ordered retry timeline with comparable bars and no fabricated skipped durations', async () => {
    const selected = fixture({ upstreamAttempts: 2, attempts: [
      { index: 1, skipped: true, skipReason: 'cooldown', elapsedMs: 9000, channelName: '冷却渠道' },
      { index: 2, statusCode: 200, upstreamErrorStatusCode: 429, elapsedMs: 400, errorMessage: '上游限流后切换渠道', retryable: true },
      { index: 3, statusCode: 200, elapsedMs: 1600, firstTokenMs: 0, committed: true },
    ] })
    const document = await render([selected], selected)
    const timeline = document.querySelector('ol.attempt-list')
    assert.ok(timeline)
    const rows = timeline.querySelectorAll('li.attempt-row')
    assert.equal(rows.length, 3)
    assert.ok(rows[0].classList.contains('attempt-row-skipped'))
    assert.equal(rows[0].querySelector('.attempt-duration-track'), null)
    assert.ok(rows[1].classList.contains('attempt-row-failed'))
    assert.match(rows[1].textContent, /失败，已切换.*上游限流后切换渠道/s)
    assert.equal(rows[1].querySelector('.attempt-duration-track > span').style.width, '25%')
    assert.ok(rows[2].classList.contains('attempt-row-success'))
    assert.equal(rows[2].querySelector('.attempt-duration-track > span').style.width, '100%')
    assert.match(document.querySelector('.attempt-timing-summary').textContent, /2 s/)
    assert.ok(![...rows[1].querySelectorAll('.attempt-detail .attempt-label')].some(label => label.textContent === '首 token'))
  })

  it('keeps missing retry timing explicit and never invents historical attempts', async () => {
    const selected = fixture({ attempts: [
      { index: 1, statusCode: 503, elapsedMs: null, retryable: true },
      { index: 2, statusCode: 200, elapsedMs: 0, committed: true },
    ] })
    const document = await render([selected], selected)
    assert.match(document.querySelector('.attempt-duration').textContent, /耗时未记录/)
    assert.match(document.querySelector('.attempt-timing-summary').textContent, /1 \/ 2 次有记录/)
    assert.equal(document.querySelector('.attempt-duration-track'), null)
    const historical = fixture({ upstreamAttempts: 3, attempts: [] })
    const history = await render([historical], historical)
    assert.equal(history.querySelector('ol.attempt-list'), null)
    assert.match(history.querySelector('.log-attempt-disclosure').textContent, /无尝试明细/)
  })

  it('preserves per-request and tiered billing instead of forcing four token rates', async () => {
    const perRequest = fixture({ pricingMode: 'per_request', perRequestPrice: '0.02' })
    const first = await render([perRequest], perRequest)
    assert.ok(first.querySelector('.detail-price-list.is-per-request'))
    assert.equal(first.querySelectorAll('.detail-price-row').length, 1)
    assert.match(first.querySelector('.detail-price-list').textContent, /每次请求.*0.02/)
    const tiered = fixture({ pricingMode: 'tiered', priceTiers: [{ upToTokens: 10000, inputPricePerMillion: '1', outputPricePerMillion: '2' }, { upToTokens: null, inputPricePerMillion: '3', outputPricePerMillion: '4' }] })
    const second = await render([tiered], tiered)
    assert.ok(second.querySelector('.detail-price-list.is-tiered'))
    assert.equal(second.querySelectorAll('.detail-price-row').length, 2)
    assert.match(second.querySelector('.detail-price-list').textContent, /10,000.*10,001/)
    assert.match(second.querySelector('.detail-price-snapshot .detail-block-head').textContent, /阶梯计费/)
    const volume = { ...tiered, pricingMode: 'volume' }
    const third = await render([volume], volume)
    assert.match(third.querySelector('.detail-price-snapshot .detail-block-head').textContent, /阶梯总量计费/)
  })
})
