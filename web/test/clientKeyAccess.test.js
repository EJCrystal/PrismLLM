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

  it('冲突密钥只按密钥白名单计算，密钥黑名单与渠道范围组合不视为冲突', async () => {
    const { conflictingClientKeyAccessKeys } = await helpers()
    const allowlist = {
      channels: { mode: 'allowlist', ids: ['channel-a'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-1' }, { channelId: 'channel-b', keyId: 'key-1' }] },
      models: { mode: 'unrestricted', ids: [] },
    }
    assert.deepEqual(conflictingClientKeyAccessKeys(allowlist), [{ channelId: 'channel-b', keyId: 'key-1' }])
    assert.deepEqual(conflictingClientKeyAccessKeys({ ...allowlist, channels: { mode: 'unrestricted', ids: [] } }), [])
    assert.deepEqual(conflictingClientKeyAccessKeys({ ...allowlist, channels: { mode: 'denylist', ids: ['channel-b'] } }), [{ channelId: 'channel-b', keyId: 'key-1' }])
    assert.deepEqual(conflictingClientKeyAccessKeys({ ...allowlist, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-b', keyId: 'key-1' }] } }), [])
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

  it('黑名单规范化保留排除项并复制草稿，空黑名单不误报无授权目标', async () => {
    const { normalizeClientKeyAccessPolicy, clientKeyAccessSummary } = await helpers()
    const policy = {
      channels: { mode: 'denylist', ids: [' channel-a ', 'channel-a'] },
      channelKeys: { mode: 'denylist', keys: [{ channelId: ' channel-a ', keyId: ' key-1 ' }] },
      models: { mode: 'denylist', ids: [' Model-A ', 'Model-A', 'model-a'] },
    }
    const normalized = normalizeClientKeyAccessPolicy(policy)
    assert.deepEqual(normalized, {
      channels: { mode: 'denylist', ids: ['channel-a'] },
      channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-1' }] },
      models: { mode: 'denylist', ids: ['Model-A', 'model-a'] },
    })
    assert.match(clientKeyAccessSummary(normalized), /黑名单|禁止|排除/)
    const empty = clientKeyAccessSummary({
      channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [] }, models: { mode: 'denylist', ids: [] },
    })
    assert.doesNotMatch(empty, /无授权目标/)
    assert.match(empty, /黑名单|禁止|排除/)
    normalized.models.ids.push('other')
    assert.equal(policy.models.ids.length, 3)
  })
})

describe('渠道与密钥合并卡片映射', () => {
  async function tree() { return import('../src/utils/clientKeyAccessTree') }
  const unrestricted = () => ({
    channels: { mode: 'unrestricted', ids: [] },
    channelKeys: { mode: 'unrestricted', keys: [] },
    models: { mode: 'unrestricted', ids: [] },
  })
  const channelA = {
    id: 'channel-a', name: '渠道 A', enabled: true,
    keys: [{ id: 'key-a', name: 'keyA', state: 'ACTIVE', credentialSet: true, models: ['upstream-a'] }],
    modelMappings: [{ upstreamModel: 'upstream-a', canonicalModelId: 'Model-A' }],
    oauthAccounts: [{ id: 'oauth-a', name: 'oauth-a@example.com', enabled: true, models: ['upstream-a'] }],
  }
  const channelB = {
    id: 'channel-b', name: '渠道 B', enabled: false,
    keys: [{ id: 'key-b', name: 'keyB', state: 'ACTIVE', credentialSet: true, models: ['upstream-b'] }],
    modelMappings: [{ upstreamModel: 'upstream-b', canonicalModelId: null }],
    oauthAccounts: [],
  }
  const channels = [channelA, channelB]
  for (const channel of channels) {
    Object.assign(channel, { baseUrl: '', authType: 'bearer', protocolAuthTypes: {}, protocols: ['chat_completions'], credentialSet: true, createdAt: 1, updatedAt: 1, priority: 0, kind: 'custom', provider: null, requestOverrides: { inheritGlobal: true, headerProfile: null, userAgent: null, headers: {}, body: {}, protocols: {} }, quota: { providerId: '', config: {} } })
    channel.modelMappings.forEach(mapping => { mapping.mappingSource = null })
    channel.keys.forEach(key => Object.assign(key, { priority: 0, rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null }, stateVersion: 0, autoDisableUntil: null, probeUntil: null, autoDisableReason: null }))
  }

  it('短 ID 去掉连字符取前六位，合并模式以受限渠道维度优先', async () => {
    const { shortId, mergedAccessMode } = await tree()
    assert.equal(shortId('df418129-c1fd-40cc-aeb0-ac6b37819796'), '#df4181')
    assert.equal(mergedAccessMode(unrestricted()), 'unrestricted')
    assert.equal(mergedAccessMode({ ...unrestricted(), channels: { mode: 'denylist', ids: [] } }), 'denylist')
    assert.equal(mergedAccessMode({ ...unrestricted(), channelKeys: { mode: 'allowlist', keys: [] } }), 'allowlist')
    assert.equal(mergedAccessMode({ channels: { mode: 'allowlist', ids: [] }, channelKeys: { mode: 'denylist', keys: [] }, models: unrestricted().models }), 'allowlist')
  })

  it('渠道行三态：渠道级全选、部分子密钥半选、黑名单整渠道排除', async () => {
    const { channelCheckInfo } = await tree()
    const allowChannelOnly = { ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] } }
    assert.deepEqual(channelCheckInfo(allowChannelOnly, channelA), { state: 'checked', selectedKeys: 0, totalKeys: 1 })
    assert.equal(channelCheckInfo(allowChannelOnly, channelB).state, 'unchecked')
    const allowWithKeys = { ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] }, channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    assert.equal(channelCheckInfo(allowWithKeys, channelA).state, 'checked')
    const partial = { ...allowWithKeys, channelKeys: { mode: 'allowlist', keys: [] } }
    assert.equal(channelCheckInfo(partial, channelA).state, 'unchecked')
    const denyChannel = { ...unrestricted(), channels: { mode: 'denylist', ids: ['channel-a'] } }
    assert.equal(channelCheckInfo(denyChannel, channelA).state, 'checked')
    const denyKeys = { ...unrestricted(), channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    assert.equal(channelCheckInfo(denyKeys, channelA).state, 'indeterminate')
  })

  it('白名单勾选父渠道保持渠道级语义，仅在密钥维度启用时展开子密钥', async () => {
    const { toggleChannelSelection } = await tree()
    const allowBase = () => ({ ...unrestricted(), channels: { mode: 'allowlist', ids: [] } })
    const checked = toggleChannelSelection(allowBase(), channels, 'channel-a')
    assert.deepEqual(checked.channels.ids, ['channel-a'])
    assert.equal(checked.channelKeys.mode, 'unrestricted')
    assert.deepEqual(checked.channelKeys.keys, [])
    const engaged = { ...checked, channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    const rechecked = toggleChannelSelection({ ...engaged, channels: { mode: 'allowlist', ids: [] } }, channels, 'channel-a')
    assert.deepEqual(rechecked.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }])
    const unchecked = toggleChannelSelection(checked, channels, 'channel-a')
    assert.deepEqual(unchecked.channels.ids, [])
  })

  it('黑名单勾选父渠道覆盖子密钥名单，取消后子项恢复', async () => {
    const { toggleChannelSelection, toggleKeySelection } = await tree()
    const denyBase = () => ({ ...unrestricted(), channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [] } })
    const denyKey = toggleKeySelection(denyBase(), channels, { channelId: 'channel-a', keyId: 'key-a' }, true)
    assert.deepEqual(denyKey.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }])
    assert.deepEqual(denyKey.channels.ids, [])
    const denyChannel = toggleChannelSelection(denyKey, channels, 'channel-a')
    assert.deepEqual(denyChannel.channels.ids, ['channel-a'])
    assert.deepEqual(denyChannel.channelKeys.keys, [])
    const restored = toggleChannelSelection(denyChannel, channels, 'channel-a')
    assert.deepEqual(restored.channels.ids, [])
    assert.deepEqual(restored.channelKeys.keys, [])
  })

  it('白名单勾选子密钥自动补渠道并首次启用时展开已勾选渠道', async () => {
    const { toggleChannelSelection, toggleKeySelection } = await tree()
    const checkedA = toggleChannelSelection(unrestricted(), channels, 'channel-a')
    const checkedB = toggleChannelSelection(checkedA, channels, 'channel-b')
    const keyed = toggleKeySelection({ ...checkedB, channels: { mode: 'allowlist', ids: ['channel-a'] } }, channels, { channelId: 'channel-b', keyId: 'key-b' }, true)
    assert.deepEqual(keyed.channels.ids, ['channel-a', 'channel-b'])
    assert.deepEqual(keyed.channelKeys.keys, [
      { channelId: 'channel-a', keyId: 'key-a' },
      { channelId: 'channel-b', keyId: 'key-b' },
    ])
  })

  it('取消覆盖态子密钥展开渠道名单并排除该项，零选中渠道同步取消勾选', async () => {
    const { toggleChannelSelection, toggleKeySelection } = await tree()
    const allowBase = () => ({ ...unrestricted(), channels: { mode: 'allowlist', ids: [] } })
    const checkedA = toggleChannelSelection(allowBase(), channels, 'channel-a')
    const uncheckedChild = toggleKeySelection(checkedA, channels, { channelId: 'channel-a', keyId: 'key-a' }, false)
    assert.deepEqual(uncheckedChild.channels.ids, [])
    assert.deepEqual(uncheckedChild.channelKeys, { mode: 'unrestricted', keys: [] })
    const multi = toggleChannelSelection(checkedA, channels, 'channel-b')
    const partial = toggleKeySelection({ ...multi, channels: { mode: 'allowlist', ids: ['channel-a', 'channel-b'] } }, channels, { channelId: 'channel-b', keyId: 'key-b' }, false)
    // 渠道 B 的唯一子密钥被取消后零选中，随树状态一并移出渠道名单。
    assert.deepEqual(partial.channels.ids, ['channel-a'])
    assert.deepEqual(partial.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }])
    // 继续取消渠道 A 的子密钥后名单清空，两个渠道都不能因渠道级残留而重新放行。
    const emptied = toggleKeySelection(partial, channels, { channelId: 'channel-a', keyId: 'key-a' }, false)
    assert.deepEqual(emptied.channels.ids, [])
    assert.deepEqual(emptied.channelKeys, { mode: 'unrestricted', keys: [] })
  })

  it('黑名单子密钥在所属渠道已被排除时不可再排除', async () => {
    const { toggleChannelSelection, toggleKeySelection } = await tree()
    const denyBase = () => ({ ...unrestricted(), channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [] } })
    const denyChannel = toggleChannelSelection(denyBase(), channels, 'channel-a')
    const ignored = toggleKeySelection(denyChannel, channels, { channelId: 'channel-a', keyId: 'key-a' }, true)
    assert.deepEqual(ignored, denyChannel)
  })

  it('全选与清空保留受限模式，白名单全选在密钥维度启用时展开全部子密钥', async () => {
    const { selectAllChannels, clearChannelSelection } = await tree()
    const allowAll = selectAllChannels(unrestricted(), channels)
    assert.deepEqual(allowAll.channels.ids, ['channel-a', 'channel-b'])
    assert.equal(allowAll.channelKeys.mode, 'unrestricted')
    const engagedBase = { ...unrestricted(), channels: { mode: 'allowlist', ids: [] }, channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    const expandAll = selectAllChannels(engagedBase, channels)
    assert.deepEqual(expandAll.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }, { channelId: 'channel-b', keyId: 'key-b' }])
    const denyAll = selectAllChannels({ ...unrestricted(), channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }, channels)
    assert.deepEqual(denyAll.channels.ids, ['channel-a', 'channel-b'])
    assert.deepEqual(denyAll.channelKeys.keys, [])
    const cleared = clearChannelSelection({ ...unrestricted(), channels: { mode: 'denylist', ids: ['channel-a'] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } })
    assert.deepEqual(cleared.channels.ids, [])
    assert.deepEqual(cleared.channelKeys.keys, [])
    assert.equal(cleared.channels.mode, 'denylist')
  })

  it('模式切换保留草稿：切白名单补齐密钥所属渠道，黑名单沿用名单', async () => {
    const { changeMergedMode } = await tree()
    const draft = { ...unrestricted(), channels: { mode: 'unrestricted', ids: ['channel-a'] }, channelKeys: { mode: 'unrestricted', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    const unrestrictedBack = changeMergedMode(draft, 'unrestricted')
    assert.equal(unrestrictedBack.channels.mode, 'unrestricted')
    assert.deepEqual(unrestrictedBack.channels.ids, ['channel-a'])
    assert.deepEqual(unrestrictedBack.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }])
    const allow = changeMergedMode({ ...unrestricted(), channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-b', keyId: 'key-b' }] } }, 'allowlist')
    assert.deepEqual(allow.channels.ids, ['channel-b'])
    assert.deepEqual(allow.channelKeys.keys, [{ channelId: 'channel-b', keyId: 'key-b' }])
    const denyEmpty = changeMergedMode(unrestricted(), 'denylist')
    assert.equal(denyEmpty.channelKeys.mode, 'unrestricted')
    assert.deepEqual(denyEmpty.channelKeys.keys, [])
  })

  it('跨维度混合模式统一为渠道模式并清空密钥名单，渠道未限制的组合不视为混合', async () => {
    const { unifyMixedModePolicy } = await tree()
    const mixed = { channels: { mode: 'allowlist', ids: ['channel-a'] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-b', keyId: 'key-b' }] }, models: unrestricted().models }
    const unified = unifyMixedModePolicy(mixed)
    assert.equal(unified.unified, true)
    assert.equal(unified.policy.channelKeys.mode, 'unrestricted')
    assert.deepEqual(unified.policy.channelKeys.keys, [])
    assert.deepEqual(unified.policy.channels.ids, ['channel-a'])
    const keysOnly = { channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] }, models: unrestricted().models }
    assert.equal(unifyMixedModePolicy(keysOnly).unified, false)
    assert.equal(unifyMixedModePolicy(unrestricted()).unified, false)
  })

  it('模型可用性按启用渠道与生效凭据计算，OAuth 仅受渠道与白名单密钥维度约束', async () => {
    const { modelAvailabilityMap, effectiveStaticKeyCount, staticKeyEffective, oauthEffective } = await tree()
    const all = modelAvailabilityMap(unrestricted(), channels)
    assert.deepEqual(all.get('Model-A'), { supported: true, staticKeyCount: 1 })
    assert.equal(all.has('upstream-b'), false)
    const allowA = modelAvailabilityMap({ ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] } }, channels)
    assert.deepEqual(allowA.get('Model-A'), { supported: true, staticKeyCount: 1 })
    const keysDenyA = { ...unrestricted(), channels: { mode: 'denylist', ids: [] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] } }
    assert.deepEqual(modelAvailabilityMap(keysDenyA, channels).get('Model-A'), { supported: true, staticKeyCount: 0 })
    const keysAllowEmpty = { ...unrestricted(), channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'allowlist', keys: [] } }
    assert.equal(modelAvailabilityMap(keysAllowEmpty, channels).get('Model-A').supported, false)
    // 渠道黑名单排除 key-a 后，唯一启用的渠道 A 无生效密钥；停用渠道 B 的密钥不可路由不计入。
    assert.equal(effectiveStaticKeyCount(keysDenyA, channels), 0)
    assert.equal(effectiveStaticKeyCount(unrestricted(), channels), 1)
    assert.equal(oauthEffective(keysDenyA, 'channel-a'), true)
    assert.equal(oauthEffective({ ...unrestricted(), channelKeys: { mode: 'allowlist', keys: [] } }, 'channel-a'), false)
    assert.equal(staticKeyEffective(keysDenyA, { channelId: 'channel-a', keyId: 'key-a' }), false)
    assert.equal(staticKeyEffective(keysDenyA, { channelId: 'channel-b', keyId: 'key-b' }), true)
  })

  it('空名单维度判定覆盖合并卡片两种受限模式', async () => {
    const { emptyAccessDimensionLabels } = await tree()
    assert.deepEqual(emptyAccessDimensionLabels(unrestricted()), [])
    assert.deepEqual(emptyAccessDimensionLabels({ ...unrestricted(), channels: { mode: 'allowlist', ids: [] } }), ['渠道与密钥'])
    assert.deepEqual(emptyAccessDimensionLabels({ ...unrestricted(), channelKeys: { mode: 'denylist', keys: [] } }), ['渠道与密钥'])
    assert.deepEqual(emptyAccessDimensionLabels({
      ...unrestricted(),
      channels: { mode: 'denylist', ids: [] },
      channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] },
    }), [])
    assert.deepEqual(emptyAccessDimensionLabels({ ...unrestricted(), models: { mode: 'allowlist', ids: [] } }), ['模型'])
  })

  it('黑名单模式切换后勾选子密钥显式落入黑名单，排除项真实生效', async () => {
    const { changeMergedMode, toggleKeySelection, staticKeyEffective } = await tree()
    // changeMergedMode(denylist) 在密钥维度无草稿时保留 unrestricted，勾选子密钥必须显式提升模式。
    const switched = changeMergedMode(unrestricted(), 'denylist')
    assert.equal(switched.channelKeys.mode, 'unrestricted')
    const denied = toggleKeySelection(switched, channels, { channelId: 'channel-a', keyId: 'key-a' }, true)
    assert.equal(denied.channelKeys.mode, 'denylist')
    assert.deepEqual(denied.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }])
    assert.equal(staticKeyEffective(denied, { channelId: 'channel-a', keyId: 'key-a' }), false)
  })

  it('旧混合策略（渠道不限制+密钥黑名单）按黑名单卡片模式派发勾选，不反转名单含义', async () => {
    const { toggleChannelSelection, toggleKeySelection, staticKeyEffective } = await tree()
    const legacy = { channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] }, models: unrestricted().models }
    // 勾选另一个子密钥：继续加入黑名单，而不是把名单反转成白名单。
    const keyed = toggleKeySelection(legacy, channels, { channelId: 'channel-b', keyId: 'key-b' }, true)
    assert.equal(keyed.channelKeys.mode, 'denylist')
    assert.deepEqual(keyed.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-a' }, { channelId: 'channel-b', keyId: 'key-b' }])
    assert.equal(staticKeyEffective(keyed, { channelId: 'channel-a', keyId: 'key-a' }), false)
    // 勾选父渠道：渠道维度提升为黑名单并排除该渠道，其余渠道语义不变。
    const denied = toggleChannelSelection(legacy, channels, 'channel-b')
    assert.equal(denied.channels.mode, 'denylist')
    assert.deepEqual(denied.channels.ids, ['channel-b'])
    assert.equal(staticKeyEffective(denied, { channelId: 'channel-b', keyId: 'key-b' }), false)
    // 旧密钥排除项保留：排除渠道 B 不应顺带解除渠道 A 的密钥排除。
    assert.equal(staticKeyEffective(denied, { channelId: 'channel-a', keyId: 'key-a' }), false)
  })

  it('失效渠道取消勾选直接移除 ID，不因目录查找落空而重新加入', async () => {
    const { toggleChannelSelection } = await tree()
    const policy = { ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a', 'deleted-channel'] } }
    const removed = toggleChannelSelection(policy, channels, 'deleted-channel')
    assert.deepEqual(removed.channels.ids, ['channel-a'])
  })

  it('可路由判定与后端契约一致：停用/冷却密钥不计入生效统计与模型可用性', async () => {
    const { keyRoutable, effectiveStaticKeyCount, modelAvailabilityMap } = await tree()
    const now = 1_000_000
    assert.equal(keyRoutable({ state: 'ACTIVE', autoDisableUntil: null, probeUntil: null }, now), true)
    assert.equal(keyRoutable({ state: 'MANUAL_DISABLED', autoDisableUntil: null, probeUntil: null }, now), false)
    assert.equal(keyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 900_000, probeUntil: null }, now), true)
    assert.equal(keyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 1_100_000, probeUntil: null }, now), false)
    assert.equal(keyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 900_000, probeUntil: 950_000 }, now), true)
    assert.equal(keyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 900_000, probeUntil: 1_100_000 }, now), false)
    const cooling = { ...channelA, oauthAccounts: [], keys: [{ ...channelA.keys[0], state: 'AUTO_TEMPORARY', autoDisableUntil: Date.now() + 60_000, probeUntil: null }] }
    const recovered = { ...channelA, keys: [{ ...channelA.keys[0], state: 'AUTO_TEMPORARY', autoDisableUntil: Date.now() - 60_000, probeUntil: null }] }
    assert.equal(effectiveStaticKeyCount(unrestricted(), [cooling, channelB]), 0)
    assert.equal(effectiveStaticKeyCount(unrestricted(), [recovered, channelB]), 1)
    const availability = modelAvailabilityMap(unrestricted(), [cooling])
    assert.equal(availability.get('Model-A')?.supported, false)
    assert.equal(availability.get('Model-A')?.staticKeyCount, 0)
  })

  it('OAuth 可用性按映射模型判断：models 为空表示全部支持，非空须覆盖上游模型', async () => {
    const { modelAvailabilityMap } = await tree()
    const withOAuth = {
      ...channelA,
      modelMappings: [
        { upstreamModel: 'upstream-a', canonicalModelId: 'Model-A', mappingSource: null },
        { upstreamModel: 'upstream-extra', canonicalModelId: null, mappingSource: null },
      ],
      keys: [],
      oauthAccounts: [{ id: 'oauth-a', name: 'oauth-a@example.com', enabled: true, models: ['upstream-a'] }],
    }
    const availability = modelAvailabilityMap(unrestricted(), [withOAuth])
    assert.deepEqual(availability.get('Model-A'), { supported: true, staticKeyCount: 0 })
    assert.equal(availability.get('upstream-extra')?.supported, false)
    const openAccount = { ...withOAuth, oauthAccounts: [{ id: 'oauth-a', name: 'oauth-a@example.com', enabled: true, models: [] }] }
    const openAvailability = modelAvailabilityMap(unrestricted(), [openAccount])
    assert.deepEqual(openAvailability.get('Model-A'), { supported: true, staticKeyCount: 0 })
    assert.equal(openAvailability.get('upstream-extra')?.supported, true)
    const disabledAccount = { ...withOAuth, oauthAccounts: [{ id: 'oauth-a', name: 'oauth-a@example.com', enabled: false, models: [] }] }
    assert.equal(modelAvailabilityMap(unrestricted(), [disabledAccount]).get('Model-A')?.supported, false)
  })
})
