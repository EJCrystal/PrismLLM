import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildChannelPayload, buildChannelProxyPayload, channelProxyConfigurationSnapshot } from '../src/utils/channelForm'
import {
  blockedChannelProxyHosts,
  channelProxyHostAllowed,
  isValidChannelProxyHostEntry,
  parseChannelProxyHostEntries,
} from '../src/utils/channelEgress'

const proxies = [
  { id: 'us', name: '美国', url: 'http://127.0.0.1:7897' },
  { id: 'jp', name: '日本', url: 'http://localhost:8080' },
]
const form = { name: '渠道', baseUrl: 'https://upstream.example', enabled: true, protocols: ['chat_completions'], egressProxies: proxies }
const key = { id: 'key', name: '密钥', models: ['model'], egressProxyId: 'us' }
const payload = (draft = form, keys = [key]) => buildChannelPayload(draft, ['model'], {}, {}, keys)

describe('渠道出口代理', () => {
  it('HTTP 和 SOCKS5 的账号密码单独提交，密码保留原始空格，不提交回显标记', () => {
    for (const url of ['http://127.0.0.1:7897', 'socks5://127.0.0.1:1080']) {
      const result = buildChannelProxyPayload({ ...proxies[0], url, authenticationEnabled: true, username: ' proxy-user ', password: ' leading:p@ss#word ', passwordSet: false })
      assert.equal(result.username, 'proxy-user')
      assert.equal(result.password, ' leading:p@ss#word ')
      assert.equal(Object.hasOwn(result, 'passwordSet'), false)
      assert.equal(Object.hasOwn(result, 'authenticationEnabled'), false)
      assert.equal(result.url.includes('proxy-user'), false)
    }
  })

  it('已保存密码留空保持，关闭认证显式清除且不发送残留草稿', () => {
    const saved = { ...proxies[0], username: 'proxy-user', passwordSet: true }
    assert.deepEqual(buildChannelProxyPayload({ ...saved, password: '' }), { ...proxies[0], username: 'proxy-user' })
    assert.deepEqual(buildChannelProxyPayload({ ...saved, authenticationEnabled: false, password: 'do-not-send' }), { ...proxies[0], clearAuthentication: true })
    assert.equal(channelProxyConfigurationSnapshot([saved]), channelProxyConfigurationSnapshot([{ ...saved, authenticationEnabled: true, password: '' }]))
    assert.notEqual(channelProxyConfigurationSnapshot([saved]), channelProxyConfigurationSnapshot([{ ...saved, password: 'changed' }]))
  })

  it('认证必填、长度按 UTF-8 字节校验，HTTP 账号不能含冒号', () => {
    const authenticated = { ...proxies[0], authenticationEnabled: true, username: 'proxy-user', password: 'secret' }
    for (const invalid of [
      { ...authenticated, username: '' }, { ...authenticated, password: '' },
      { ...authenticated, username: 'user:tenant' }, { ...authenticated, password: '密'.repeat(86) },
      { ...authenticated, password: 'bad\npassword' }, { ...authenticated, username: 'x'.repeat(256) },
    ]) assert.throws(() => buildChannelProxyPayload(invalid), /代理账号|代理密码/)
    assert.equal(buildChannelProxyPayload({ ...authenticated, password: 'x'.repeat(255) }).password.length, 255)
    assert.equal(buildChannelProxyPayload({ ...authenticated, url: 'socks5://localhost:1080', username: 'user:tenant' }).username, 'user:tenant')
  })

  it('允许 HTTP 与 SOCKS5 代理并存并按原地址提交', () => {
    for (const url of ['socks5://127.0.0.1:1080', 'socks5://localhost', 'socks5://[::1]:1080', 'socks5://localhost:1080/']) {
      const result = payload({ ...form, egressProxies: [proxies[0], { ...proxies[1], url }] }, [{ ...key, egressProxyId: 'jp' }])
      assert.equal(result.egressProxies[1].url, url)
      assert.equal(result.keys[0].egressProxyId, 'jp')
    }
  })

  it('支持多个代理和多个密钥共用一个代理', () => {
    const result = payload(form, [key, { ...key, id: 'second' }, { ...key, id: 'third', egressProxyId: 'jp' }])
    assert.deepEqual(result.egressProxies, proxies)
    assert.deepEqual(result.keys.map(value => value.egressProxyId), ['us', 'us', 'jp'])
    assert.equal(result.keys[0].clearEgressProxy, false)
  })

  it('显式解除绑定，旧版草稿不修改代理配置', () => {
    const cleared = payload(form, [{ ...key, egressProxyId: null }]).keys[0]
    assert.equal(cleared.egressProxyId, null)
    assert.equal(cleared.clearEgressProxy, true)
    const { egressProxyId, ...legacyKey } = key
    const { egressProxies, ...legacyForm } = form
    const legacy = payload(legacyForm, [legacyKey])
    assert.equal(Object.hasOwn(legacy, 'egressProxies'), false)
    assert.equal(Object.hasOwn(legacy.keys[0], 'egressProxyId'), false)
    assert.equal(Object.hasOwn(legacy.keys[0], 'clearEgressProxy'), false)
  })

  it('放行名单解析支持精确主机、IPv4 和通配，并拒绝协议端口和路径', () => {
    assert.deepEqual(
      parseChannelProxyHostEntries(' Proxy.Example\n*.onesproxy.vip, 10.0.0.8；proxy.example '),
      ['proxy.example', '*.onesproxy.vip', '10.0.0.8'],
    )
    for (const entry of ['http://proxy.example:8080', 'proxy.example:8080', 'proxy.example/path', 'user@proxy.example', '**.example.com', '*.']) {
      assert.equal(isValidChannelProxyHostEntry(entry), false, entry)
      assert.throws(() => parseChannelProxyHostEntries(entry), /有效主机/)
    }
    assert.throws(() => parseChannelProxyHostEntries(Array.from({ length: 65 }, (_, index) => `host${index}.example`).join('\n')), /最多允许/)
    assert.deepEqual(parseChannelProxyHostEntries('proxy.example., *.example.com.'), ['proxy.example', '*.example.com'])
    assert.equal(channelProxyHostAllowed('proxy.example.', ['proxy.example']), true)
    assert.equal(channelProxyHostAllowed('na.example.com', ['*.example.com.']), true)
  })

  it('通配只匹配子域名，回环地址始终放行', () => {
    const allowed = ['*.onesproxy.vip', 'exact.example']
    assert.equal(channelProxyHostAllowed('na-data.onesproxy.vip', allowed), true)
    assert.equal(channelProxyHostAllowed('us.data.onesproxy.vip', allowed), true)
    assert.equal(channelProxyHostAllowed('onesproxy.vip', allowed), false)
    assert.equal(channelProxyHostAllowed('evilonesproxy.vip', allowed), false)
    assert.equal(channelProxyHostAllowed('EXACT.example', allowed), true)
    assert.equal(channelProxyHostAllowed('127.0.0.1', []), true)
    assert.equal(channelProxyHostAllowed('[::1]', []), true)
  })

  it('只列出尚未放行的代理主机并去重', () => {
    assert.deepEqual(
      blockedChannelProxyHosts(
        ['http://127.0.0.1:7897', 'http://na-data.onesproxy.vip:1337', 'http://na-data.onesproxy.vip:1338', 'not-a-url'],
        ['*.onesproxy.vip'],
      ),
      [],
    )
    assert.deepEqual(
      blockedChannelProxyHosts(
        ['http://na-data.onesproxy.vip:1337', 'socks5://other.example:1080', 'socks5://other.example:1081'],
        ['*.onesproxy.vip'],
      ),
      ['other.example'],
    )
  })

  it('拒绝重复代理、空名称、无效地址和其他渠道的代理绑定', () => {
    assert.throws(() => payload({ ...form, egressProxies: [...proxies, proxies[0]] }), /不能重复/)
    assert.throws(() => payload({ ...form, egressProxies: [{ ...proxies[0], name: ' ' }] }), /代理名称/)
    for (const url of ['socks4://localhost:1080', 'socks5h://localhost:1080', 'socks5://user:pass@localhost:1080', 'socks5://localhost/path', 'socks5://localhost:0', 'socks5://localhost:65536', 'socks5://localhost?', 'https://localhost:7897', 'http://user:pass@localhost', 'http://@localhost', 'http:localhost', 'http://localhost/path', 'http://localhost?x=1', 'http://localhost?', 'http://localhost#', 'http://localhost:0', 'http://localhost:65536', 'invalid']) {
      assert.throws(() => payload({ ...form, egressProxies: [{ ...proxies[0], url }] }), /代理/)
    }
    assert.throws(() => payload(form, [{ ...key, egressProxyId: 'other-channel' }]), /不存在/)
  })
})
