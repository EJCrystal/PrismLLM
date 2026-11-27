import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { agyEnvironmentStatus, countUpstreamOverrides } from '../src/utils/systemConfig'

describe('systemConfig: 系统配置展示', () => {
  it('区分 agy 检查、按需安装、准备、失败和可用状态', () => {
    assert.equal(agyEnvironmentStatus('DISABLED'), '已关闭')
    assert.equal(agyEnvironmentStatus('INSTALLING'), '正在下载安装缺失依赖')
    assert.equal(agyEnvironmentStatus('PREPARING'), '正在下载校验 agy 并检查沙盒')
    assert.equal(agyEnvironmentStatus('READY'), '运行环境已就绪')
    assert.equal(agyEnvironmentStatus('FAILED'), '准备失败')
    assert.equal(agyEnvironmentStatus('unknown'), '状态未知')
  })

  it('统计通用和协议请求体补充项并兼容旧配置', () => {
    assert.equal(countUpstreamOverrides({
      extraBody: { tenant: 'shared' },
      protocols: { responses: { extraBody: { prompt_cache_key: '${sessionId}', '!service_tier': 'default' } } },
    }), 3)
    assert.equal(countUpstreamOverrides({ protocols: {} }), 0)
  })

  it('统计上游请求覆盖项', () => {
    assert.equal(
      countUpstreamOverrides({
        inheritGlobal: true,
        headerProfile: 'claude',
        userAgent: 'PrismLLM',
        headers: { 'x-test': '1' },
        body: { temperature: 0.2 },
        protocols: {
          messages: {
            headerProfile: null,
            userAgent: null,
            headers: { 'anthropic-version': '2023-06-01' },
            body: {},
          },
        },
      }),
      5,
    )
  })
})
