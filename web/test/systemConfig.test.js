import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  configSourceLabel,
  configSourceTone,
  countUpstreamOverrides,
  formatBytes,
  formatConfigValue,
} from '../src/utils/systemConfig'

describe('systemConfig: 系统配置展示', () => {
  it('按类型格式化运行参数', () => {
    assert.equal(formatConfigValue({ value: '30000', kind: 'duration' }), '30.00 s')
    assert.equal(formatConfigValue({ value: '10485760', kind: 'bytes' }), '10 MB')
    assert.equal(formatConfigValue({ value: 'true', kind: 'boolean' }), '开启')
    assert.equal(formatConfigValue({ value: 'false', kind: 'boolean' }), '关闭')
    assert.equal(formatConfigValue({ value: 'a, b', kind: 'list' }), 'a、b')
    assert.equal(formatConfigValue({ value: '', kind: 'text' }), '未配置')
    assert.equal(formatConfigValue({ value: 'round_robin', kind: 'text' }), 'round_robin')
  })

  it('字节上限保留可读单位', () => {
    assert.equal(formatBytes(512), '512 B')
    assert.equal(formatBytes(1536), '1.5 KB')
    assert.equal(formatBytes('invalid'), '—')
  })

  it('配置来源有明确文案和徽标色调', () => {
    assert.equal(configSourceLabel('environment'), '环境变量')
    assert.equal(configSourceLabel('default'), '默认值')
    assert.equal(configSourceLabel('derived'), '运行时')
    assert.equal(configSourceTone('environment'), 'info')
    assert.equal(configSourceTone('default'), 'neutral')
  })

  it('统计上游请求覆盖项', () => {
    assert.equal(
      countUpstreamOverrides({
        inheritGlobal: true,
        headerProfile: 'claude',
        userAgent: 'PrismLLM',
        headers: { 'x-test': '1' },
        body: { temperature: 0.2 },
        reasoningContentPolicy: 'auto',
        developerRolePolicy: null,
        protocols: {
          messages: {
            headerProfile: null,
            userAgent: null,
            headers: { 'anthropic-version': '2023-06-01' },
            body: {},
          },
        },
      }),
      6,
    )
  })
})
