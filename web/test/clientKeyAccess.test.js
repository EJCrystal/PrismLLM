import { describe, it } from 'node:test'
import assert from 'node:assert/strict'

describe('调用方访问范围 helper', () => {
  async function helpers() { return import('../src/utils/clientKeyAccess') }

  it('新表单独立创建三个不限制维度', async () => {
    const { createUnrestrictedAccessPolicy } = await helpers()
    const first = createUnrestrictedAccessPolicy()
    first.channels.ids.push('channel-a')
    assert.deepEqual(createUnrestrictedAccessPolicy(), {
      channels: { mode: 'unrestricted', ids: [] },
      channelKeys: { mode: 'unrestricted', keys: [] },
      models: { mode: 'unrestricted', ids: [] },
    })
  })

  it('规范化去掉首尾空白和重复项，保留模型 ID 的大小写与密钥所属渠道', async () => {
    const { normalizeClientKeyAccessPolicy } = await helpers()
    const draft = {
      channels: { mode: 'allowlist', ids: [' channel-a ', 'channel-a', 'channel-b'] },
      channelKeys: { mode: 'allowlist', keys: [
        { channelId: ' channel-a ', keyId: ' key-1 ' },
        { channelId: 'channel-a', keyId: 'key-1' },
        { channelId: 'channel-b', keyId: 'key-1' },
      ] },
      models: { mode: 'allowlist', ids: [' Model-A ', 'Model-A', 'model-a'] },
    }
    assert.deepEqual(normalizeClientKeyAccessPolicy(draft), {
      channels: { mode: 'allowlist', ids: ['channel-a', 'channel-b'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-1' }, { channelId: 'channel-b', keyId: 'key-1' }] },
      models: { mode: 'allowlist', ids: ['Model-A', 'model-a'] },
    })
    assert.equal(draft.channels.ids[0], ' channel-a ')
  })

  it('完整保存显式清空不限制维度的数组，保留空白名单的拒绝语义', async () => {
    const { normalizeClientKeyAccessPolicy } = await helpers()
    assert.deepEqual(normalizeClientKeyAccessPolicy({
      channels: { mode: 'unrestricted', ids: ['channel-a'] },
      channelKeys: { mode: 'unrestricted', keys: [{ channelId: 'channel-a', keyId: 'key-1' }] },
      models: { mode: 'allowlist', ids: [] },
    }), {
      channels: { mode: 'unrestricted', ids: [] },
      channelKeys: { mode: 'unrestricted', keys: [] },
      models: { mode: 'allowlist', ids: [] },
    })
  })

  it('编辑草稿复制名单，不反向修改列表中已保存的授权', async () => {
    const { normalizeClientKeyAccessPolicy } = await helpers()
    const saved = {
      channels: { mode: 'allowlist', ids: ['channel-a'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-1' }] },
      models: { mode: 'allowlist', ids: ['Model-A'] },
    }
    const draft = normalizeClientKeyAccessPolicy(saved)
    draft.channels.ids.push('channel-b')
    draft.channelKeys.keys[0].keyId = 'other'
    assert.deepEqual(saved.channels.ids, ['channel-a'])
    assert.equal(saved.channelKeys.keys[0].keyId, 'key-1')
  })

  it('列表摘要区分不限制、多项授权与空白名单', async () => {
    const { clientKeyAccessSummary } = await helpers()
    assert.match(clientKeyAccessSummary({
      channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'unrestricted', keys: [] }, models: { mode: 'unrestricted', ids: [] },
    }), /不限制/)
    const summary = clientKeyAccessSummary({
      channels: { mode: 'allowlist', ids: ['a', 'b'] }, channelKeys: { mode: 'allowlist', keys: [] }, models: { mode: 'allowlist', ids: ['Model-A'] },
    })
    assert.match(summary, /渠道.*2/)
    assert.match(summary, /无授权目标/)
    assert.match(summary, /模型.*1/)
  })
})
