import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'

describe('channel health list summary', () => {
  let server
  let component
  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/ChannelHealthSummary.vue')).default
  })
  after(async () => { await server?.close() })
  const channel = { name: '测试渠道', enabled: true }
  const snapshot = {
    metrics: { attempts: 100, failures: 4, successRate: 0.96, p95FirstTokenMs: 25000 },
    state: 'HEALTHY', reason: '成功率正常',
    recent: { state: 'CRITICAL', reason: '上游失败率达到 20%', metrics: { attempts: 20, failures: 4 } },
    recentAttempts: [{ logId: 1, attemptIndex: 1, createdAt: 1_800_000_000_000, outcome: 'FAILURE', statusCode: 200, errorCode: 'responses_failed', upstreamModel: 'deepseek-flash', elapsedMs: 12000, firstTokenMs: 10000 }],
  }
  const render = props => renderToString(createSSRApp(component, { channel, loading: false, error: '', ...props }))

  it('uses the daily verdict and metrics even when recent health differs', async () => {
    const html = await render({ summary: snapshot })
    assert.match(html, /24h 状态/)
    assert.match(html, /data-state="HEALTHY"/)
    assert.match(html, /96\.0%/)
    assert.match(html, /成功率正常/)
    assert.doesNotMatch(html, /近 1 小时|data-state="CRITICAL"|上游失败率达到 20%/)
    assert.match(html, /100 次尝试/)
    assert.match(html, /data-outcome="FAILURE"/)
    assert.match(html, /responses_failed/)
    assert.match(html, /健康详情/)
    assert.doesNotMatch(html, /health-overview-caption|24h 首 token P95/)
    assert.equal((html.match(/data-outcome="EMPTY"/g) ?? []).length, 29)
  })

  it('keeps sample counts available in the tooltip without adding metric rows', async () => {
    const html = await render({ summary: snapshot })
    assert.match(html, /title="24h：100 次尝试 \/ 4 失败；成功率 96\.0%；成功率正常/)
    assert.equal((html.match(/class="health-overview-metric(?:\s|"\s|\shealth)/g) ?? []).length, 2)
  })

  it('keeps the daily verdict when the recent snapshot is missing', async () => {
    const html = await render({ summary: { ...snapshot, recent: null, recentAttempts: [] } })
    assert.match(html, /data-state="HEALTHY"/)
    assert.doesNotMatch(html, /data-state="UNKNOWN"/)
    assert.match(html, /暂无记录/)
  })

  it('does not hide daily warnings behind a healthy recent snapshot', async () => {
    const html = await render({ summary: { ...snapshot, state: 'WARNING', reason: '首 token 延迟偏高', recent: { ...snapshot.recent, state: 'HEALTHY', reason: '成功率正常' } } })
    assert.match(html, /data-state="WARNING"/)
    assert.match(html, /首 token 延迟偏高/)
    assert.doesNotMatch(html, /data-state="HEALTHY"/)
  })

  it('keeps last successful data and explains failed refreshes', async () => {
    const html = await render({ summary: snapshot, error: '网络异常', observedAt: 1_800_000_000_000 })
    assert.match(html, /96\.0%/)
    assert.match(html, /刷新失败 · 显示上次数据/)
    assert.match(html, /data-state="HEALTHY"/)
  })

  it('hides empty trends for both enabled and disabled channels', async () => {
    for (const enabled of [true, false]) {
      for (const summary of [undefined, { ...snapshot, recentAttempts: [] }]) {
        const html = await render({ channel: { ...channel, enabled }, summary })
        assert.doesNotMatch(html, /class="health-overview-trend"|data-outcome="EMPTY"/)
        assert.match(html, /暂无记录/)
        assert.match(html, /健康详情/)
      }
    }
  })

  it('retains disabled channel history and daily success rates', async () => {
    const html = await render({ channel: { ...channel, enabled: false }, summary: snapshot })
    assert.match(html, /data-state="DISABLED"/)
    assert.match(html, /96\.0%/)
    assert.match(html, /class="health-overview-trend"/)
    assert.match(html, /data-outcome="FAILURE"/)
    assert.match(html, /最近 1 次/)
    assert.match(html, /responses_failed/)
    assert.equal((html.match(/data-outcome="EMPTY"/g) ?? []).length, 29)
  })

  it('never presents loading or failed reads as empty request history', async () => {
    for (const props of [{ loading: true }, { error: '网络异常' }]) {
      const html = await render(props)
      assert.doesNotMatch(html, /class="health-overview-trend"|data-outcome="EMPTY"|暂无记录/)
      assert.match(html, /健康详情/)
    }
    assert.match(await render({ loading: true }), /汇总中…/)
    assert.match(await render({ error: '网络异常' }), /近期尝试读取失败/)
  })

  it('distinguishes loading, read errors and disabled channels without fabricating samples', async () => {
    assert.match(await render({ loading: true }), /汇总中…/)
    const error = await render({ error: '网络异常' })
    assert.match(error, /读取失败/)
    assert.doesNotMatch(error, /100\.0%/)
    assert.match(await render({ channel: { ...channel, enabled: false }, summary: snapshot }), /data-state="DISABLED"/)
  })
})
