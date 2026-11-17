import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  notificationCredentialSecretError,
  notificationCredentialUrlError,
  notificationDeliveryFinished,
  notificationDeliveryStatusLabel,
  notificationEventLabel,
  notificationTargetError,
  notificationTestDisabledReason,
  notificationTemplateError,
  notificationThresholdError,
} from '../src/utils/notification'

const baseTarget = {
  name: '值班群', type: 'webhook', enabled: false,
  events: ['channel.unhealthy'], channelIds: [],
}
const baseSettings = { enabled: true, consecutiveFailureThreshold: 5, updatedAt: 1 }
const configuredTarget = {
  ...baseTarget, enabled: true, id: 'target-1', revision: 1, updatedAt: 1,
  credentials: {
    urlConfigured: true, bearerTokenConfigured: false, signingSecretConfigured: false,
    urlUnreadable: false, bearerTokenUnreadable: false, signingSecretUnreadable: false,
  },
  latestDelivery: null,
}

describe('notification settings validation and display', () => {
  it('accepts default and boundary thresholds and rejects fractional or out-of-range values', () => {
    for (const value of [1, 5, 100]) assert.equal(notificationThresholdError(value), '')
    for (const value of [0, 101, 1.5, Number.NaN, '5', null]) assert.match(notificationThresholdError(value), /整数/)
  })

  it('validates credential URLs and secret character sets before saving', () => {
    assert.equal(notificationCredentialUrlError(''), '')
    assert.equal(notificationCredentialUrlError(' https://hooks.example.test/notify '), '')
    assert.match(notificationCredentialUrlError('ftp://hooks.example.test/notify'), /HTTP 或 HTTPS/)
    assert.match(notificationCredentialUrlError('not-a-url'), /完整的 HTTP 或 HTTPS/)
    assert.match(notificationCredentialUrlError('https://user@hooks.example.test/'), /账号或密码/)
    assert.match(notificationCredentialUrlError(`https://hooks.example.test/${'a'.repeat(2048)}`), /2048/)
    assert.equal(notificationCredentialSecretError('', 'Bearer 令牌'), '')
    assert.equal(notificationCredentialSecretError('abcDEF123-_', 'Bearer 令牌'), '')
    assert.match(notificationCredentialSecretError('has space', 'Bearer 令牌'), /可打印 ASCII/)
    assert.match(notificationCredentialSecretError('bad\u0000secret', '签名密钥'), /控制字符/)
    assert.match(notificationCredentialSecretError('x'.repeat(4097), '签名密钥'), /4096/)

    assert.equal(notificationTargetError(baseTarget), '')
    assert.equal(notificationTargetError({ ...baseTarget, type: 'slack' }), '请选择 Webhook 或飞书机器人。')
    assert.match(notificationTargetError({ ...baseTarget, name: 'x'.repeat(101) }), /100 个字符/)
    assert.match(notificationTargetError({ ...baseTarget, name: 'bad\u0000name' }), /控制字符/)
  })

  it('requires known events and an explicit nonempty scope when not targeting all channels', () => {
    assert.equal(notificationTargetError(baseTarget), '')
    assert.match(notificationTargetError({ ...baseTarget, events: [] }), /事件/)
    assert.match(notificationTargetError({ ...baseTarget, events: ['notification.test'] }), /事件/)
    assert.match(notificationTargetError({ ...baseTarget, events: Array(7).fill('channel.unhealthy') }), /事件/)
    assert.match(notificationTargetError({ ...baseTarget, channelIds: [] }, [], false), /至少一个渠道/)
    assert.match(notificationTargetError({ ...baseTarget, channelIds: ['channel-1'] }, [{ id: 'channel-2' }], false), /已删除或不存在/)
    assert.match(notificationTargetError({ ...baseTarget, channelIds: ['x'.repeat(101)] }, undefined, false), /渠道 ID/)
    assert.match(notificationTargetError({ ...baseTarget, channelIds: Array.from({ length: 101 }, (_, index) => `c-${index}`) }, undefined, false), /最多选择 100 个渠道/)
    assert.equal(notificationTargetError({ ...baseTarget, channelIds: ['deleted-channel'] }, undefined, true), '')
  })

  it('validates optional Feishu card templates and their variables', () => {
    assert.equal(notificationTemplateError('', ''), '')
    assert.equal(notificationTemplateError('【{{severity}}】{{channel}}', '**渠道** {{channel}}\n原因：{{reason}}'), '')
    assert.match(notificationTemplateError('x'.repeat(101), ''), /100/)
    assert.match(notificationTemplateError('bad\ntitle', ''), /控制字符或换行/)
    assert.match(notificationTemplateError('', 'x'.repeat(4097)), /4096/)
    assert.match(notificationTemplateError('', 'bad\u0000body'), /控制字符/)
    assert.match(notificationTemplateError('{{unknown}}', ''), /未知模板变量/)
    assert.match(notificationTemplateError('', '{{eventType}} {{channel}} {{nope}}'), /nope/)
  })

  it('only enables test enqueue when globally and locally enabled and URL is configured', () => {
    assert.match(notificationTestDisabledReason(null, configuredTarget), /全局通知设置/)
    assert.match(notificationTestDisabledReason({ ...baseSettings, enabled: false }, configuredTarget), /全局通知设置/)
    assert.match(notificationTestDisabledReason(baseSettings, { ...configuredTarget, enabled: false }), /启用并保存此通知目标/)
    assert.match(notificationTestDisabledReason(baseSettings, { ...configuredTarget, credentials: { ...configuredTarget.credentials, urlConfigured: false } }), /填写接收端 URL/)
    assert.match(
      notificationTestDisabledReason(baseSettings, { ...configuredTarget, credentials: { ...configuredTarget.credentials, urlUnreadable: true } }),
      /无法解密/,
    )
    assert.match(
      notificationTestDisabledReason(baseSettings, { ...configuredTarget, credentials: { ...configuredTarget.credentials, bearerTokenUnreadable: true } }),
      /无法解密/,
    )
    assert.match(
      notificationTestDisabledReason(baseSettings, { ...configuredTarget, credentials: { ...configuredTarget.credentials, signingSecretUnreadable: true } }),
      /无法解密/,
    )
    assert.equal(notificationTestDisabledReason(baseSettings, { ...configuredTarget, enabled: true }), '')
  })

  it('labels test and delivery outcomes without treating queued states as terminal', () => {
    assert.equal(notificationEventLabel('notification.test'), '测试通知')
    assert.equal(notificationEventLabel('attempt.recovered'), '连续失败恢复')
    assert.equal(notificationDeliveryStatusLabel('in_flight'), '投递中')
    assert.equal(notificationDeliveryFinished('pending'), false)
    assert.equal(notificationDeliveryFinished('in_flight'), false)
    for (const status of ['succeeded', 'failed', 'cancelled']) assert.equal(notificationDeliveryFinished(status), true)
  })
})
