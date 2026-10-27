import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPricePrompt,
  channelDisplayName,
  channelModelChoiceMode,
  channelModelPriceOptions,
  channelModelPricingId,
  channelPriceSummaries,
  defaultPriceModelOptions,
  findDefaultModelPrice,
  findDefaultPriceForModel,
  findModelPrice,
  isSamePriceTarget,
  pricesForChannel,
  resolveChannelModelPrice,
  resolveDisplayPriceSource,
  resolveSavedChannel,
  unpricedChannelModels,
} from '../src/utils/modelPrice.js'
import { channelDisplayModels } from '../src/utils/channelDisplay.js'

describe('modelPrice: 按渠道和上游模型查找单价', () => {
  const prices = [
    { id: 'price-a', channelId: 'channel-a', upstreamModel: 'model-a' },
    { id: 'price-b', channelId: 'channel-b', upstreamModel: 'model-a' },
  ]

  it('只匹配同一渠道的同一上游模型', () => {
    assert.equal(findModelPrice(prices, 'channel-a', 'model-a'), prices[0])
  })

  it('渠道或模型不匹配时返回 null', () => {
    assert.equal(findModelPrice(prices, 'channel-c', 'model-a'), null)
    assert.equal(findModelPrice(prices, 'channel-a', 'model-b'), null)
  })

  it('缺少目标或无效列表时返回 null', () => {
    assert.equal(findModelPrice(prices, '', 'model-a'), null)
    assert.equal(findModelPrice(null, 'channel-a', 'model-a'), null)
  })

  it('只把当前渠道未配置价格的模型列为未定价', () => {
    const channel = { id: 'channel-a', models: ['model-a', 'model-b', 'model-c'] }
    assert.deepEqual(unpricedChannelModels(channel, prices), ['model-b', 'model-c'])
  })

  it('其他渠道的价格不会让当前渠道变为已定价', () => {
    const channel = { id: 'channel-c', models: ['model-a'] }
    assert.deepEqual(unpricedChannelModels(channel, prices), ['model-a'])
  })

  it('缺少模型列表或价格列表时安全返回空或全部未定价', () => {
    assert.deepEqual(unpricedChannelModels(null, prices), [])
    assert.deepEqual(unpricedChannelModels({ id: 'channel-a' }, prices), [])
    assert.deepEqual(
      unpricedChannelModels({ id: 'channel-a', models: ['model-a'] }, null),
      ['model-a'],
    )
  })

  it('重复模型只计一次', () => {
    const channel = { id: 'channel-a', models: ['model-b', 'model-b'] }
    assert.deepEqual(unpricedChannelModels(channel, prices), ['model-b'])
  })

  it('忽略空白差异并过滤空模型名', () => {
    const spacedPrices = [{ channelId: 'channel-a', upstreamModel: ' model-a ' }]
    const channel = { id: 'channel-a', models: ['model-a', '  ', '', null, '   '] }
    assert.deepEqual(unpricedChannelModels(channel, spacedPrices), [])
    assert.equal(findModelPrice(spacedPrices, 'channel-a', ' model-a'), spacedPrices[0])
  })

  it('按渠道列出已配置单价并按上游模型排序', () => {
    const channelPrices = [
      { id: 'p2', channelId: 'channel-a', upstreamModel: 'model-z' },
      { id: 'p1', channelId: 'channel-a', upstreamModel: 'model-a' },
      { id: 'p3', channelId: 'channel-b', upstreamModel: 'model-b' },
      null,
      { id: 'p4', channelId: 'channel-a' },
      { id: 'p5', channelId: 'channel-a', upstreamModel: '   ' },
    ]
    assert.deepEqual(
      pricesForChannel(channelPrices, 'channel-a').map((price) => price.id),
      ['p1', 'p2'],
    )
  })

  it('渠道或价格列表无效时返回空数组', () => {
    assert.deepEqual(pricesForChannel(null, 'channel-a'), [])
    assert.deepEqual(pricesForChannel([], 'channel-a'), [])
    assert.deepEqual(pricesForChannel([{ channelId: 'channel-a', upstreamModel: 'm' }], ''), [])
  })

  it('忽略空白模型名的价格记录', () => {
    const channelPrices = [
      { id: 'blank', channelId: 'channel-a', upstreamModel: '   ' },
      { id: 'valid', channelId: 'channel-a', upstreamModel: ' model-a ' },
    ]
    assert.deepEqual(pricesForChannel(channelPrices, 'channel-a').map((price) => price.id), ['valid'])
  })

  it('模型下拉按 trim 后匹配已配置价格', () => {
    const models = [{ id: ' model-a ' }, { id: 'model-b' }]
    const channelPrices = [{ id: 'p1', channelId: 'channel-a', upstreamModel: ' model-a ' }]
    const options = channelModelPriceOptions(models, channelPrices, 'channel-a')
    assert.equal(options[0].price, channelPrices[0])
    assert.equal(options[1].price, null)
  })

  it('模型下拉输入无效时安全返回空数组', () => {
    assert.deepEqual(channelModelPriceOptions(null, [], 'channel-a'), [])
    assert.deepEqual(channelModelPriceOptions([{ id: 'm' }], null, 'channel-a')[0].price, null)
  })

  it('trim 后同名的重复价格仍视为同一编辑目标', () => {
    const price = { channelId: 'channel-a', upstreamModel: ' model-a ' }
    assert.equal(isSamePriceTarget(price, 'channel-a', 'model-a'), true)
    assert.equal(isSamePriceTarget(price, 'channel-b', 'model-a'), false)
    assert.equal(isSamePriceTarget(price, 'channel-a', 'model-b'), false)
    assert.equal(isSamePriceTarget(null, 'channel-a', 'model-a'), false)
  })

  it('同一对外模型映射多个上游时，只标记尚未定价的上游', () => {
    const channel = {
      id: 'channel-a',
      models: ['deepseek-flash', 'deepseek-v4.1-flash'],
      modelMappings: [
        { upstreamModel: 'deepseek-flash', canonicalModelId: 'deepseek-flash' },
        { upstreamModel: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' },
      ],
    }
    const partialPrices = [{ channelId: 'channel-a', upstreamModel: 'deepseek-flash' }]
    const display = channelDisplayModels(channel)[0]
    const unpriced = unpricedChannelModels(channel, partialPrices)
    assert.deepEqual(display.upstreamModels, ['deepseek-flash', 'deepseek-v4.1-flash'])
    assert.deepEqual(unpriced, ['deepseek-v4.1-flash'])
    assert.deepEqual(
      display.upstreamModels.filter((model) => unpriced.includes(model)),
      ['deepseek-v4.1-flash'],
    )
  })
})

describe('modelPrice: 未定价汇总', () => {
  const channels = [
    { id: 'c1', name: '渠道一', models: ['m1', 'm2'] },
    { id: 'c2', name: '渠道二', models: ['m3'] },
    { id: 'c3', name: '渠道三', models: [] },
  ]
  const prices = [{ id: 'p1', channelId: 'c1', upstreamModel: 'm1' }]

  it('按渠道汇总未定价模型数量', () => {
    const summaries = channelPriceSummaries(channels, prices)
    assert.deepEqual(summaries.get('c1'), { unpriced: ['m2'], count: 1 })
    assert.deepEqual(summaries.get('c2'), { unpriced: ['m3'], count: 1 })
    assert.deepEqual(summaries.get('c3'), { unpriced: [], count: 0 })
  })

  it('新增渠道后立即出现在汇总中', () => {
    const next = [...channels, { id: 'c4', name: '渠道四', models: ['m4'] }]
    assert.deepEqual(channelPriceSummaries(next, prices).get('c4'), { unpriced: ['m4'], count: 1 })
  })

  it('删除渠道后汇总不再包含该渠道', () => {
    const next = channels.filter((channel) => channel.id !== 'c1')
    const summaries = channelPriceSummaries(next, prices)
    assert.equal(summaries.has('c1'), false)
    assert.equal(summaries.size, 2)
  })

  it('编辑渠道模型后按最新模型重新计算', () => {
    const edited = [{ id: 'c1', name: '渠道一', models: ['m1', 'm9'] }]
    assert.deepEqual(channelPriceSummaries(edited, prices).get('c1'), { unpriced: ['m9'], count: 1 })
  })

  it('补齐全部单价后计数归零', () => {
    const full = [...prices, { id: 'p2', channelId: 'c1', upstreamModel: 'm2' }]
    assert.deepEqual(channelPriceSummaries([channels[0]], full).get('c1'), { unpriced: [], count: 0 })
  })

  it('异常输入返回空汇总', () => {
    assert.equal(channelPriceSummaries(null, prices).size, 0)
    assert.equal(channelPriceSummaries([null, { models: ['m'] }], prices).size, 0)
  })
})

describe('modelPrice: 模型默认价与渠道覆盖', () => {
  const channel = {
    id: 'channel-a',
    models: ['deepseek-v4.1-flash', 'gpt-4o-mini'],
    modelMappings: [
      { upstreamModel: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' },
      { upstreamModel: 'gpt-4o-mini' },
    ],
  }
  const defaults = [
    { id: 'd1', modelId: 'deepseek-flash', currency: 'USD', inputPricePerMillion: '0.15' },
    { id: 'd2', modelId: ' gpt-4o-mini ', currency: 'USD', inputPricePerMillion: '0.10' },
  ]

  it('按 trim 后的模型 ID 查找默认价', () => {
    assert.equal(findDefaultModelPrice(defaults, ' gpt-4o-mini '), defaults[1])
    assert.equal(findDefaultModelPrice(defaults, 'missing'), null)
    assert.equal(findDefaultModelPrice(null, 'deepseek-flash'), null)
  })

  it('优先使用渠道配置的规范 ID 作为默认价模型 ID', () => {
    assert.equal(channelModelPricingId(channel, 'deepseek-v4.1-flash'), 'deepseek-flash')
    assert.equal(channelModelPricingId(channel, 'gpt-4o-mini'), 'gpt-4o-mini')
    assert.equal(channelModelPricingId(channel, 'missing'), 'missing')
  })

  it('没有渠道覆盖价时继承默认价', () => {
    const resolved = resolveChannelModelPrice(channel, 'deepseek-v4.1-flash', [], defaults)
    assert.equal(resolved.source, 'default')
    assert.equal(resolved.price, defaults[0])
    assert.equal(resolved.modelId, 'deepseek-flash')
  })

  it('渠道覆盖价优先于模型默认价', () => {
    const prices = [{ id: 'p1', channelId: 'channel-a', upstreamModel: 'deepseek-v4.1-flash' }]
    const resolved = resolveChannelModelPrice(channel, 'deepseek-v4.1-flash', prices, defaults)
    assert.equal(resolved.source, 'channel')
    assert.equal(resolved.price, prices[0])
    assert.equal(resolved.defaultPrice, null)
  })

  it('规范 ID 没有默认价时回退到上游模型名', () => {
    const upstreamDefaults = [
      { id: 'd3', modelId: 'deepseek-v4.1-flash', currency: 'USD', inputPricePerMillion: '0.2' },
    ]
    const resolved = resolveChannelModelPrice(channel, 'deepseek-v4.1-flash', [], upstreamDefaults)
    assert.equal(resolved.source, 'default')
    assert.equal(resolved.price, upstreamDefaults[0])
    assert.equal(resolved.modelId, 'deepseek-flash')
  })

  it('未配置任何价格时返回 none', () => {
    const resolved = resolveChannelModelPrice(channel, 'missing', [], defaults)
    assert.equal(resolved.source, 'none')
    assert.equal(resolved.price, null)
  })

  it('空模型组不会误判为渠道价', () => {
    assert.equal(resolveDisplayPriceSource(channel, [], [], defaults), 'none')
    assert.equal(resolveDisplayPriceSource(channel, null, [], defaults), 'none')
  })

  it('模型组来源区分渠道价、默认价和混合价', () => {
    const prices = [{ id: 'p1', channelId: 'channel-a', upstreamModel: 'gpt-4o-mini' }]
    assert.equal(
      resolveDisplayPriceSource(channel, ['gpt-4o-mini'], prices, defaults),
      'channel',
    )
    assert.equal(
      resolveDisplayPriceSource(channel, ['deepseek-v4.1-flash'], prices, defaults),
      'default',
    )
    assert.equal(
      resolveDisplayPriceSource(channel, ['gpt-4o-mini', 'deepseek-v4.1-flash'], prices, defaults),
      'mixed',
    )
  })

  it('默认价会让未定价汇总归零', () => {
    assert.deepEqual(unpricedChannelModels(channel, [], defaults), [])
    assert.deepEqual(channelPriceSummaries([channel], [], defaults).get('channel-a'), {
      unpriced: [],
      count: 0,
    })
  })

  it('模型下拉同时带出渠道价和默认价', () => {
    const models = [
      { id: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' },
      { id: 'gpt-4o-mini' },
    ]
    const options = channelModelPriceOptions(models, [], 'channel-a', defaults)
    assert.equal(options[0].defaultPrice, defaults[0])
    assert.equal(options[0].price, null)
    assert.equal(options[1].defaultPrice, defaults[1])
  })

  it('模型下拉在规范 ID 无默认价时回退上游模型默认价', () => {
    const upstreamDefaults = [
      { id: 'd3', modelId: 'deepseek-v4.1-flash', currency: 'USD', inputPricePerMillion: '0.2' },
    ]
    const models = [{ id: 'deepseek-v4.1-flash', canonicalModelId: 'deepseek-flash' }]
    const options = channelModelPriceOptions(models, [], 'channel-a', upstreamDefaults)
    assert.equal(options[0].defaultPrice, upstreamDefaults[0])
    assert.equal(findDefaultPriceForModel(upstreamDefaults, 'deepseek-v4.1-flash', 'deepseek-flash'), upstreamDefaults[0])
  })

  it('默认价候选收集渠道映射与授权模型，并排除已配置默认价的模型', () => {
    const channels = [
      {
        id: 'c1',
        name: '渠道一',
        models: ['mapped-model', 'plain-model', 'unconfigured-model', '  '],
        modelMappings: [{ upstreamModel: 'mapped-model', canonicalModelId: 'mapped-canonical' }],
      },
      null,
    ]
    const prices = [{ id: 'd1', modelId: 'plain-model' }]
    const options = defaultPriceModelOptions(channels, prices)
    assert.deepEqual(options.map((option) => option.id), ['mapped-canonical', 'unconfigured-model'])
    assert.deepEqual(options[0].upstreamModels, ['mapped-model'])
    assert.deepEqual(options[0].channelNames, ['渠道一'])
  })

  it('编辑已有默认价时候选列表允许保留当前正在编辑的模型', () => {
    const channels = [
      {
        id: 'c1',
        name: '渠道一',
        models: ['plain-model', 'other-model'],
      },
    ]
    const prices = [{ id: 'd1', modelId: 'plain-model' }, { id: 'd2', modelId: 'other-model' }]
    const options = defaultPriceModelOptions(channels, prices, 'plain-model')
    assert.deepEqual(options.map((option) => option.id), ['plain-model'])
  })

  it('排除已配置默认价时忽略大小写与首尾空白', () => {
    const channels = [
      {
        id: 'c1',
        name: '渠道一',
        models: ['deepseek-flash', 'qwen-flash'],
      },
    ]
    const prices = [{ id: 'd1', modelId: '  DEEPSEEK-FLASH  ' }]
    const options = defaultPriceModelOptions(channels, prices)
    assert.deepEqual(options.map((option) => option.id), ['qwen-flash'])
  })

  it('默认价候选对异常输入安全返回', () => {
    assert.deepEqual(defaultPriceModelOptions(null, null), [])
    assert.deepEqual(defaultPriceModelOptions([{ id: 'c1' }], []), [])
  })
})

describe('modelPrice: 保存后提示状态', () => {
  it('仍有未定价模型时生成提示', () => {
    assert.deepEqual(buildPricePrompt({ id: 'c1', name: '渠道一' }, 2), {
      channelId: 'c1',
      channelName: '渠道一',
      count: 2,
    })
  })

  it('计数为零或渠道不存在时返回 null', () => {
    assert.equal(buildPricePrompt({ id: 'c1', name: '渠道一' }, 0), null)
    assert.equal(buildPricePrompt(null, 3), null)
    assert.equal(buildPricePrompt({ id: 'c1' }, Number.NaN), null)
  })

  it('渠道改名后提示使用最新名称', () => {
    const channel = { id: 'c1', name: '新名称' }
    assert.equal(buildPricePrompt(channel, 1).channelName, '新名称')
  })

  it('新增、补价、编辑、删除渠道后提示随当前数据刷新', () => {
    let channels = [{ id: 'c1', name: '渠道一', models: ['m1', 'm2'] }]
    let prices = []
    const currentPrompt = () => {
      const channel = channels.find((item) => item.id === 'c1')
      const summary = channelPriceSummaries(channels, prices).get('c1')
      return buildPricePrompt(channel, summary?.count ?? 0)
    }

    assert.equal(currentPrompt().count, 2, '新增渠道后两个模型都未定价')

    prices = [{ channelId: 'c1', upstreamModel: 'm1' }]
    assert.equal(currentPrompt().count, 1, '补一个单价后计数递减')

    channels = [{ id: 'c1', name: '渠道一', models: ['m1'] }]
    assert.equal(currentPrompt(), null, '编辑移除未定价模型后提示消失')

    channels = [{ id: 'c1', name: '渠道一', models: ['m1', 'm3'] }]
    assert.equal(currentPrompt().count, 1, '编辑新增模型后重新出现提示')

    channels = []
    assert.equal(currentPrompt(), null, '删除渠道后提示消失')
  })
})

describe('modelPrice: 保存后定位渠道', () => {
  const channels = [
    { id: 'c1', name: '渠道一' },
    { id: 'c2', name: '渠道二' },
  ]

  it('优先按返回 ID 定位新建渠道', () => {
    assert.equal(resolveSavedChannel(channels, { id: 'c2' }), channels[1])
  })

  it('缺少返回 ID 时按编辑 ID 定位', () => {
    assert.equal(resolveSavedChannel(channels, null, 'c1'), channels[0])
  })

  it('ID 均缺失时按名称兜底', () => {
    assert.equal(resolveSavedChannel(channels, null, null, ' 渠道二 '), channels[1])
  })

  it('渠道已不存在时返回 null，避免陈旧提示', () => {
    assert.equal(resolveSavedChannel(channels, { id: 'missing' }, 'missing'), null)
    assert.equal(resolveSavedChannel([], null, null, '渠道一'), null)
  })
})

describe('modelPrice: 渠道显示名称解析', () => {
  const channels = [
    { id: 'c1', name: 'Anthropic-麻豆科技' },
    { id: 'c2', name: 'deepseek-grox' },
  ]

  it('能正确根据 channelId 找到渠道名称', () => {
    assert.equal(channelDisplayName(channels, 'c1'), 'Anthropic-麻豆科技')
    assert.equal(channelDisplayName(channels, 'c2'), 'deepseek-grox')
  })

  it('找不到对应渠道时回退为 channelId', () => {
    assert.equal(channelDisplayName(channels, 'unknown-id'), 'unknown-id')
  })

  it('空值与异常输入安全返回', () => {
    assert.equal(channelDisplayName(null, null), '')
    assert.equal(channelDisplayName([], ''), '')
    assert.equal(channelDisplayName(channels, '   '), '')
  })
})

describe('modelPrice: 渠道模型选择布局', () => {
  it('空模型使用手动输入，单模型直接展示，多模型使用切换器', () => {
    assert.equal(channelModelChoiceMode([]), 'manual')
    assert.equal(channelModelChoiceMode(null), 'manual')
    assert.equal(channelModelChoiceMode([{ id: 'm1' }]), 'single')
    assert.equal(channelModelChoiceMode([{ id: 'm1' }, { id: 'm2' }]), 'multiple')
  })
})
