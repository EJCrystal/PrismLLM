import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { countUpstreamOverrides } from '../src/utils/systemConfig'

describe('systemConfig: 系统配置展示', () => {
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
