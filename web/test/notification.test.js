import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  notificationDeliveryFinished,
  notificationDeliveryStatusLabel,
  notificationEnvironmentPrefixPattern,
  notificationEventLabel,
  notificationTargetError,
  notificationTestDisabledReason,
  notificationThresholdError,
} from '../src/utils/notification'

const baseTarget = {
  name: '值班群', type: 'webhook', enabled: false,
  events: ['channel.unhealthy'], channelIds: [], environmentPrefix: 'PRISM_NOTIFY_ONCALL',
}
const baseSettings = { enabled: true, consecutiveFailureThreshold: 5, updatedAt: 1 }
const configuredTarget = {
  ...baseTarget, enabled: true, id: 'target-1', revision: 1, updatedAt: 1,
  credentials: { urlConfigured: true, bearerTokenConfigured: false, signingSecretConfigured: false }, latestDelivery: null,
}

describe('notification settings validation and display', () => {
  it('accepts default and boundary thresholds and rejects fractional or out-of-range values', () => {
    for (const value of [1, 5, 100]) assert.equal(notificationThresholdError(value), '')
    for (const value of [0, 101, 1.5, Number.NaN, '5', null]) assert.match(notificationThresholdError(value), /整数/)
  })

  it('enforces the backend prefix regex and allowed target values', () => {
    assert.equal(notificationEnvironmentPrefixPattern.test('PRISM_NOTIFY_A'), true)
    assert.equal(notificationEnvironmentPrefixPattern.test('PRISM_NOTIFY_A'.padEnd('PRISM_NOTIFY_'.length + 48, 'Z')), true)
    assert.equal(notificationEnvironmentPrefixPattern.test('PRISM_NOTIFY_A'.padEnd('PRISM_NOTIFY_'.length + 49, 'Z')), false)
    for (const prefix of ['PRISM_NOTIFY_', 'PRISM_NOTIFY_1BAD', 'prism_NOTIFY_A', 'PRISM_NOTIFY_A-B', 'PRISM_NOTIFY_A_'.padEnd(62, 'A')]) {
      assert.equal(notificationEnvironmentPrefixPattern.test(prefix), false, prefix)
      assert.match(notificationTargetError({ ...baseTarget, environmentPrefix: prefix }), /环境变量前缀/)
    }
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

  it('only enables test enqueue when globally and locally enabled and URL is configured', () => {
    assert.match(notificationTestDisabledReason(null, configuredTarget), /全局通知设置/)
    assert.match(notificationTestDisabledReason({ ...baseSettings, enabled: false }, configuredTarget), /全局通知设置/)
    assert.match(notificationTestDisabledReason(baseSettings, { ...configuredTarget, enabled: false }), /启用并保存此通知目标/)
    assert.match(notificationTestDisabledReason(baseSettings, { ...configuredTarget, credentials: { ...configuredTarget.credentials, urlConfigured: false } }), /PRISM_NOTIFY_ONCALL_URL/)
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
