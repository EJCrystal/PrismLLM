import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  VISIBLE_MODEL_LIMIT,
  channelDisplayModels,
  channelMatchesFilters,
  channelModelFilterOptions,
  visibleModels,
  hiddenModelsTitle,
  channelLandingUrl,
  recentProbeByChannel,
  ORDERED_PROTOCOLS,
  channelProtocolSlots,
} from '../src/utils/channelDisplay'

describe('channelDisplay: 渠道列表与 +N 模型展示逻辑', () => {
  it('按解码后的渠道 ID 索引最新协议探测记录，并跳过无效编码', () => {
    const newest = { id: 1, path: '/admin/api/channels/channel%2Fone/probe-protocols' }
    const olderDuplicate = { id: 2, path: '/admin/api/channels/channel%2Fone/probe-protocols' }
    const malformed = { id: 3, path: '/admin/api/channels/bad%ZZ/probe-protocols' }
    const indexed = recentProbeByChannel([newest, olderDuplicate, malformed])

    assert.equal(indexed.get('channel/one'), newest)
    assert.equal(indexed.has('bad%ZZ'), false)
  })

  it('渠道地址链接只清理末尾 v1，保留查询参数和锚点并过滤内部地址', () => {
    assert.equal(channelLandingUrl('https://user:secret@example.test/v1/?tenant=a#models'), 'https://example.test/?tenant=a#models')
    assert.equal(channelLandingUrl('https://example.test/gateway/v1'), 'https://example.test/gateway')
    assert.equal(channelLandingUrl('https://example.test/v1beta'), 'https://example.test/v1beta')
    assert.equal(channelLandingUrl('https://127.0.0.1/v1'), null)
    assert.equal(channelLandingUrl('http://10.2.3.4:8080/v1'), null)
    assert.equal(channelLandingUrl('http://[::1]:8080/v1'), null)
    assert.equal(channelLandingUrl('http://gateway.internal/v1'), null)
    assert.equal(channelLandingUrl('file:///tmp/models'), null)
  })

  it('常量 VISIBLE_MODEL_LIMIT 应为 3', () => {
    assert.equal(VISIBLE_MODEL_LIMIT, 3)
  })

  describe('列表项展示 (visibleModels)', () => {
    it('自定义对外模型 ID 时：列表显示对外名，hover 提示上游名', () => {
      const channel = {
        models: ['deepseek-v4.1-flash'],
        modelMappings: [
          { upstreamModel: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' },
        ],
      }
      const visible = visibleModels(channel)
      assert.equal(visible.length, 1)
      assert.equal(visible[0].displayName, 'deepseek-flash', '标签主文本应为对外模型 ID')
      assert.equal(visible[0].upstreamModel, 'deepseek-v4.1-flash')
      assert.equal(visible[0].isCustom, true)
      assert.equal(
        visible[0].title,
        'deepseek-flash (上游模型: deepseek-v4.1-flash)',
        'hover 应同时展示完整对外模型 ID 和上游模型名',
      )
    })

    it('未自定义对外模型 ID（留空/null）时：列表仅显示上游名，且不显示重复 hover 提示', () => {
      const channel = {
        models: ['gpt-4o-mini'],
        modelMappings: [
          { upstreamModel: 'gpt-4o-mini', canonicalModelId: null },
        ],
      }
      const visible = visibleModels(channel)
      assert.equal(visible.length, 1)
      assert.equal(visible[0].displayName, 'gpt-4o-mini', '标签主文本应为上游名')
      assert.equal(visible[0].upstreamModel, 'gpt-4o-mini')
      assert.equal(visible[0].isCustom, false)
      assert.equal(visible[0].title, '', '未自定义时不应有重复 hover 提示')
    })

    it('对外模型 ID 全为空格时：视为未自定义，回退上游名且不显示重复提示', () => {
      const channel = {
        models: ['claude-3-5-haiku'],
        modelMappings: [
          { upstreamModel: 'claude-3-5-haiku', canonicalModelId: '   ' },
        ],
      }
      const visible = visibleModels(channel)
      assert.equal(visible[0].displayName, 'claude-3-5-haiku')
      assert.equal(visible[0].isCustom, false)
      assert.equal(visible[0].title, '')
    })

    it('自定义对外模型 ID 与上游模型同名时：视为无差异，不显示重复 hover 提示', () => {
      const channel = {
        models: ['qwen-plus'],
        modelMappings: [
          { upstreamModel: 'qwen-plus', canonicalModelId: 'qwen-plus' },
        ],
      }
      const visible = visibleModels(channel)
      assert.equal(visible[0].displayName, 'qwen-plus')
      assert.equal(visible[0].isCustom, false)
      assert.equal(visible[0].title, '', '同名时不应有多余的 hover 提示')
    })

    it('多个上游模型映射为同一对外 ID 时：只显示一个模型，并在 hover 中列出全部上游名', () => {
      const channel = {
        models: ['deepseek-flash', 'deepseek-v4.1-flash'],
        modelMappings: [
          { upstreamModel: 'deepseek-flash', canonicalModelId: 'deepseek-flash' },
          { upstreamModel: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' },
        ],
      }

      const visible = visibleModels(channel)

      assert.equal(visible.length, 1)
      assert.equal(visible[0].displayName, 'deepseek-flash')
      assert.deepEqual(visible[0].upstreamModels, ['deepseek-flash', 'deepseek-v4.1-flash'])
      assert.equal(
        visible[0].title,
        'deepseek-flash (上游模型: deepseek-flash、deepseek-v4.1-flash)',
      )
    })
  })

  describe('渠道列表模型筛选', () => {
    const channels = [
      { id: 'one', enabled: true, models: ['wire-a', 'model-z'], modelMappings: [{ upstreamModel: 'wire-a', canonicalModelId: 'public-a' }] },
      { id: 'two', enabled: false, models: ['model-b', 'public-a'], modelMappings: [] },
    ]

    it('模型选项按表格中显示的对外模型去重并排序', () => {
      assert.deepEqual(channelModelFilterOptions(channels), ['model-b', 'model-z', 'public-a'])
      assert.deepEqual(channelModelFilterOptions(null), [])
    })

    it('模型和渠道状态筛选可以叠加，映射模型按对外名称匹配', () => {
      assert.equal(channelMatchesFilters(channels[0], 'enabled', 'public-a'), true)
      assert.equal(channelMatchesFilters(channels[1], 'disabled', 'public-a'), true)
      assert.equal(channelMatchesFilters(channels[0], 'disabled', 'public-a'), false)
      assert.equal(channelMatchesFilters(channels[0], 'enabled', 'wire-a'), false)
      assert.equal(channelMatchesFilters(channels[1], 'all', ''), true)
    })
  })

  describe('+N 折叠徽章展示 (hiddenModelsTitle)', () => {
    it('超过 3 个模型时：列表仅显示前 3 个，其余进入 +N', () => {
      const channel = {
        models: ['m1', 'm2', 'm3', 'm4', 'm5'],
        modelMappings: [],
      }
      const visible = visibleModels(channel)
      assert.equal(visible.length, 3)
      assert.equal(channel.models.length - VISIBLE_MODEL_LIMIT, 2, '折叠徽章数值应为 +2')
    })

    it('混合场景：自定义项在 +N hover 中显示“对外名 (上游: 上游名)”，未自定义项仅显示上游名', () => {
      const channel = {
        models: [
          'upstream-a', // 自定义 -> public-a
          'upstream-b', // 未自定义 -> 留空
          'upstream-c', // 自定义 -> public-c
          'upstream-d', // 自定义 -> public-d
          'upstream-e', // 未自定义 -> 留空
        ],
        modelMappings: [
          { upstreamModel: 'upstream-a', canonicalModelId: 'public-a' },
          { upstreamModel: 'upstream-b', canonicalModelId: null },
          { upstreamModel: 'upstream-c', canonicalModelId: 'public-c' },
          { upstreamModel: 'upstream-d', canonicalModelId: 'public-d' },
          { upstreamModel: 'upstream-e', canonicalModelId: '' },
        ],
      }

      // 验证前 3 个列表项
      const visible = visibleModels(channel)
      assert.equal(visible[0].displayName, 'public-a')
      assert.equal(visible[0].title, 'public-a (上游模型: upstream-a)')
      assert.equal(visible[1].displayName, 'upstream-b')
      assert.equal(visible[1].title, '')
      assert.equal(visible[2].displayName, 'public-c')
      assert.equal(visible[2].title, 'public-c (上游模型: upstream-c)')

      // 验证 +N 完整悬停提示
      const hoverTitle = hiddenModelsTitle(channel)
      const expected =
        'public-a (上游: upstream-a), upstream-b, public-c (上游: upstream-c), public-d (上游: upstream-d), upstream-e'
      assert.equal(hoverTitle, expected)
    })

    it('全部未自定义时：+N hover 仅展示逗号分隔的上游名，无任何“(上游: ...)”冗余', () => {
      const channel = {
        models: ['model-1', 'model-2', 'model-3', 'model-4'],
        modelMappings: [],
      }
      const hoverTitle = hiddenModelsTitle(channel)
      assert.equal(hoverTitle, 'model-1, model-2, model-3, model-4')
      assert.equal(hoverTitle.includes('(上游:'), false)
    })

    it('全部自定义且与上游不同时：+N hover 每一项均展示“对外名 (上游: 上游名)”', () => {
      const channel = {
        models: ['ds-v4', 'qw-flash', 'gpt-mini', 'claude-3-haiku-20240307'],
        modelMappings: [
          { upstreamModel: 'ds-v4', canonicalModelId: 'deepseek-flash' },
          { upstreamModel: 'qw-flash', canonicalModelId: 'qwen-flash' },
          { upstreamModel: 'gpt-mini', canonicalModelId: 'gpt-4o-mini' },
          { upstreamModel: 'claude-3-haiku-20240307', canonicalModelId: 'claude-haiku' },
        ],
      }
      const hoverTitle = hiddenModelsTitle(channel)
      const expected =
        'deepseek-flash (上游: ds-v4), qwen-flash (上游: qw-flash), gpt-4o-mini (上游: gpt-mini), claude-haiku (上游: claude-3-haiku-20240307)'
      assert.equal(hoverTitle, expected)
    })
  })

  describe('边界与容错处理', () => {
    it('channel 为空、null 或 models 缺省时返回空数组或空字符串', () => {
      assert.deepEqual(channelDisplayModels(null), [])
      assert.deepEqual(visibleModels({}), [])
      assert.equal(hiddenModelsTitle({}), '')
      assert.equal(hiddenModelsTitle(null), '')
    })

    it('modelMappings 中缺失某模型的映射时，安全回退到上游名', () => {
      const channel = {
        models: ['unmapped-model'],
        modelMappings: [{ upstreamModel: 'other-model', canonicalModelId: 'other-public' }],
      }
      const visible = visibleModels(channel)
      assert.equal(visible[0].displayName, 'unmapped-model')
      assert.equal(visible[0].isCustom, false)
      assert.equal(visible[0].title, '')
    })
  })

  describe('channelProtocolSlots: 渠道协议固定矩阵与优先级展示', () => {
    it('协议槽位严格按 chat_completions -> responses -> messages -> gemini 顺序排列', () => {
      assert.deepEqual(ORDERED_PROTOCOLS, [
        'chat_completions',
        'responses',
        'messages',
        'gemini',
      ])
      const slots = channelProtocolSlots([])
      assert.equal(slots.length, 4)
      assert.deepEqual(
        slots.map((s) => s.protocol),
        ['chat_completions', 'responses', 'messages', 'gemini'],
      )
    })

    it('未启用或缺省时所有槽位处于未配置置灰状态', () => {
      const slotsFromEmpty = channelProtocolSlots([])
      for (const slot of slotsFromEmpty) {
        assert.equal(slot.enabled, false)
        assert.equal(slot.priority, null)
        assert.match(slot.title, /· 未配置$/)
      }

      const slotsFromNull = channelProtocolSlots(null)
      assert.equal(slotsFromNull.length, 4)
      assert.equal(slotsFromNull[0].enabled, false)

      const slotsFromUndefined = channelProtocolSlots(undefined)
      assert.equal(slotsFromUndefined.length, 4)
      assert.equal(slotsFromUndefined[0].enabled, false)
    })

    it('部分启用时槽位保持固定位置，启用项标明优先级与对应悬停提示', () => {
      // 渠道优先使用 Anthropic Messages，回退到 OpenAI Chat
      const slots = channelProtocolSlots(['messages', 'chat_completions'])

      // slot 0: chat_completions (第二顺位)
      assert.equal(slots[0].protocol, 'chat_completions')
      assert.equal(slots[0].enabled, true)
      assert.equal(slots[0].priority, 2)
      assert.equal(slots[0].title, 'OpenAI Chat Completions · 优先级 2')

      // slot 1: responses (未配置)
      assert.equal(slots[1].protocol, 'responses')
      assert.equal(slots[1].enabled, false)
      assert.equal(slots[1].priority, null)
      assert.equal(slots[1].title, 'OpenAI Responses · 未配置')

      // slot 2: messages (第一顺位)
      assert.equal(slots[2].protocol, 'messages')
      assert.equal(slots[2].enabled, true)
      assert.equal(slots[2].priority, 1)
      assert.equal(slots[2].title, 'Anthropic Messages · 优先级 1')

      // slot 3: gemini (未配置)
      assert.equal(slots[3].protocol, 'gemini')
      assert.equal(slots[3].enabled, false)
      assert.equal(slots[3].priority, null)
      assert.equal(slots[3].title, 'Google Gemini · 未配置')
    })

    it('全部启用时无论自定义顺序如何，位置固定且各自优先级序号准确', () => {
      // 倒序配置
      const slots = channelProtocolSlots(['gemini', 'messages', 'responses', 'chat_completions'])
      assert.deepEqual(
        slots.map((s) => s.protocol),
        ['chat_completions', 'responses', 'messages', 'gemini'],
      )
      assert.deepEqual(
        slots.map((s) => s.priority),
        [4, 3, 2, 1],
      )
      assert.equal(slots[0].title, 'OpenAI Chat Completions · 优先级 4')
      assert.equal(slots[1].title, 'OpenAI Responses · 优先级 3')
      assert.equal(slots[2].title, 'Anthropic Messages · 优先级 2')
      assert.equal(slots[3].title, 'Google Gemini · 优先级 1')
    })
  })
})
