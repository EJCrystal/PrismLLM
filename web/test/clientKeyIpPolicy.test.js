import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildClientKeyIpPolicy, clientKeyIpPolicySummary } from '../src/utils/clientKeyIpPolicy'

describe('调用方密钥 IP 名单', () => {
  it('空输入清除限制，不转成拒绝全部', () => {
    assert.deepEqual(buildClientKeyIpPolicy(' \n ', ''), { allowlist: [], denylist: [] })
  })

  it('支持换行和中英文分隔符，保留 IPv6 与 CIDR 并去重', () => {
    assert.deepEqual(buildClientKeyIpPolicy(' 203.0.113.1\n2001:db8::/32，203.0.113.1;198.51.100.0/24 ', '192.0.2.1\r\n192.0.2.2'), {
      allowlist: ['203.0.113.1', '2001:db8::/32', '198.51.100.0/24'],
      denylist: ['192.0.2.1', '192.0.2.2'],
    })
  })

  it('名单限制按去重后的条数计算', () => {
    const entries = Array.from({ length: 256 }, (_, index) => '192.0.2.' + index).join('\n')
    assert.equal(buildClientKeyIpPolicy(entries, '').allowlist.length, 256)
    assert.throws(() => buildClientKeyIpPolicy(entries + '\n198.51.100.1', ''), /256/)
    assert.throws(() => buildClientKeyIpPolicy('', entries + '\n198.51.100.1'), /256/)
  })

  it('摘要不为无限制或旧响应增加视觉噪声', () => {
    assert.equal(clientKeyIpPolicySummary(), '')
    assert.equal(clientKeyIpPolicySummary({ allowlist: [], denylist: [] }), '')
    assert.equal(clientKeyIpPolicySummary({ allowlist: ['::1'], denylist: ['192.0.2.1'] }), 'IP 白名单 1 · IP 黑名单 1')
  })
})
