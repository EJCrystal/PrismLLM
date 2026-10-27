import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  channelModelChoiceMode,
  channelModelOptionsForChannel,
  channelModelPriceOptions,
} from '../src/utils/modelPrice.js'
import {
  refreshedPriceToApply,
  resolveModelSelectionTarget,
} from '../src/utils/modelSelection.js'
import { emptyPriceDraft } from '../src/utils/priceForm.js'

const channelId = 'channel-a'
const prices = [
  {
    id: 'price-a',
    channelId,
    upstreamModel: 'model-a',
    currency: 'CNY',
    inputPricePerMillion: '7',
    outputPricePerMillion: '14',
  },
]
const modelDefaults = [
  {
    id: 'default-b',
    modelId: 'model-b',
    currency: 'USD',
    inputPricePerMillion: '0.5',
    outputPricePerMillion: '1.5',
  },
]

function selectionOptions(channel, channelPrices = prices, defaultPrices = modelDefaults) {
  const models = channelModelOptionsForChannel(channel)
  return {
    mode: channelModelChoiceMode(models),
    options: channelModelPriceOptions(models, channelPrices, channel.id, defaultPrices),
  }
}

function emptySelectionForm(channel) {
  return {
    channelId: channel.id,
    upstreamModel: '',
    ...emptyPriceDraft(),
  }
}

function selectModel(form, modelId, channelPrices = prices) {
  const target = resolveModelSelectionTarget(form.channelId, modelId, channelPrices)
  form.upstreamModel = modelId
  Object.assign(form, target.draft)
  return target
}

describe('modelSelection: 渠道模型切换', () => {
  it('切换到已有价格时加载该模型自己的价格', () => {
    const target = resolveModelSelectionTarget(channelId, ' model-a ', prices)
    assert.equal(target.modelId, 'model-a')
    assert.equal(target.price?.id, 'price-a')
    assert.equal(target.draft.currency, 'CNY')
    assert.equal(target.draft.inputPricePerMillion, '7')
  })

  it('切换到未定价模型时使用空白草稿，不继承上一模型金额', () => {
    const target = resolveModelSelectionTarget(channelId, 'model-b', prices)
    assert.equal(target.price, null)
    assert.equal(target.draft.currency, 'USD')
    assert.equal(target.draft.inputPricePerMillion, '')
    assert.equal(target.draft.outputPricePerMillion, '')
  })

  it('价格列表刷新暂时缺少当前记录时不清空编辑状态', () => {
    assert.equal(refreshedPriceToApply(null, { id: 'price-a' }), null)
    assert.equal(refreshedPriceToApply({ id: 'price-a' }, { id: 'price-a' }), null)
    assert.equal(refreshedPriceToApply({ id: 'price-b' }, { id: 'price-a' })?.id, 'price-b')
  })
})

describe('modelSelection: 单模型与多模型渠道的价格隔离', () => {
  it('单模型渠道直接绑定唯一模型并加载它的渠道价', () => {
    const channel = { id: 'channel-single', models: ['only-model'] }
    const channelPrices = [{
      id: 'price-only',
      channelId: channel.id,
      upstreamModel: 'only-model',
      currency: 'USD',
      inputPricePerMillion: '4',
      outputPricePerMillion: '8',
    }]
    const { mode, options } = selectionOptions(channel, channelPrices, [])

    assert.equal(mode, 'single')
    assert.equal(options.length, 1)
    assert.equal(options[0].price?.id, 'price-only')

    const form = emptySelectionForm(channel)
    selectModel(form, 'only-model', channelPrices)
    assert.equal(form.upstreamModel, 'only-model')
    assert.equal(form.inputPricePerMillion, '4')
    assert.equal(form.outputPricePerMillion, '8')
  })

  it('多模型渠道连续切换时渠道价、默认价和未保存草稿互不串联', () => {
    const channel = {
      id: channelId,
      models: ['model-a', 'model-b', 'model-c'],
    }
    const { mode, options } = selectionOptions(channel)
    const [modelA, modelB, modelC] = options

    assert.equal(mode, 'multiple')
    assert.equal(modelA.price?.id, 'price-a')
    assert.equal(modelA.defaultPrice, null)
    assert.equal(modelB.price, null)
    assert.equal(modelB.defaultPrice?.id, 'default-b')
    assert.equal(modelC.price, null)
    assert.equal(modelC.defaultPrice, null)

    const form = emptySelectionForm(channel)
    selectModel(form, 'model-a')
    assert.equal(form.currency, 'CNY')
    assert.equal(form.inputPricePerMillion, '7')
    assert.equal(form.outputPricePerMillion, '14')

    form.inputPricePerMillion = '999'
    form.outputPricePerMillion = '1999'

    selectModel(form, 'model-b')
    assert.equal(form.currency, 'USD')
    assert.equal(form.inputPricePerMillion, '')
    assert.equal(form.outputPricePerMillion, '')

    form.inputPricePerMillion = modelB.defaultPrice.inputPricePerMillion
    form.outputPricePerMillion = modelB.defaultPrice.outputPricePerMillion

    selectModel(form, 'model-c')
    assert.equal(form.inputPricePerMillion, '')
    assert.equal(form.outputPricePerMillion, '')

    selectModel(form, 'model-a')
    assert.equal(form.currency, 'CNY')
    assert.equal(form.inputPricePerMillion, '7')
    assert.equal(form.outputPricePerMillion, '14')
  })
})
