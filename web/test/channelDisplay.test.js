import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  VISIBLE_MODEL_LIMIT,
  channelDisplayModels,
  visibleModels,
  hiddenModelsTitle,
} from '../src/utils/channelDisplay.js'

describe('channelDisplay: 渠道列表与 +N 模型展示逻辑', () => {
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
})
