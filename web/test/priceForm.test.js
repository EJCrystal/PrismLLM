import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  applyPriceToForm,
  buildBatchPricePayload,
  buildDefaultPricePayload,
  buildPricePayload,
  defaultPriceFormState,
  emptyPriceDraft,
  hasPriceDraft,
  parseNonNegativePrice,
  parseOptionalNonNegativePrice,
  priceFormError,
  priceFormState,
  resetPriceForm,
} from '../src/utils/priceForm.js'

describe('priceForm: 计费单价的币种与金额', () => {
  it('编辑已有非 USD 单价时保留原币种和金额', () => {
    const form = priceFormState({
      currency: 'CNY',
      inputPricePerMillion: '7.00000000',
      outputPricePerMillion: '14.00000000',
      cachedInputPricePerMillion: '1.00000000',
    })
    const payload = buildPricePayload(form, 'channel-a', 'model-a')

    assert.equal(form.currency, 'CNY')
    assert.equal(payload.currency, 'CNY')
    assert.equal(payload.inputPricePerMillion, '7.00000000')
    assert.equal(payload.outputPricePerMillion, '14.00000000')
    assert.equal(payload.cachedInputPricePerMillion, '1.00000000')
  })

  it('新建单价默认使用 USD', () => {
    assert.equal(priceFormState(null).currency, 'USD')
    const payload = buildPricePayload(priceFormState(null), 'channel-a', ' model-a ')
    assert.equal(payload.currency, 'USD')
    assert.equal(payload.upstreamModel, 'model-a')
    assert.equal(payload.cachedInputPricePerMillion, null)
  })

  it('编辑时清空缓存单价会提交 null，而不是空字符串', () => {
    const payload = buildPricePayload(
      { currency: 'USD', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputPricePerMillion: ' ' },
      'channel-a',
      'model-a',
    )
    assert.equal(payload.cachedInputPricePerMillion, null)
  })

  it('缺少币种时安全回退到 USD', () => {
    const payload = buildPricePayload(
      { currency: '', inputPricePerMillion: '1', outputPricePerMillion: '2' },
      'channel-a',
      'model-a',
    )
    assert.equal(payload.currency, 'USD')
  })

  it('从已有 CNY 单价切换到未定价模型时重置为 USD 草稿', () => {
    const existing = priceFormState({
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
    })
    assert.equal(existing.currency, 'CNY')

    const draft = emptyPriceDraft()
    assert.equal(draft.currency, 'USD')
    assert.equal(draft.inputPricePerMillion, '')
    assert.equal(draft.outputPricePerMillion, '')
    assert.equal(draft.cachedInputPricePerMillion, '')
  })

  it('检测用户是否已填写单价草稿', () => {
    assert.equal(hasPriceDraft(emptyPriceDraft()), false)
    assert.equal(hasPriceDraft({ inputPricePerMillion: '0.1' }), true)
    assert.equal(hasPriceDraft({ outputPricePerMillion: '  ' }), false)
    assert.equal(hasPriceDraft({ cachedInputPricePerMillion: '0.05' }), true)
    assert.equal(hasPriceDraft(null), false)
  })

  it('点击已配置单价时回填渠道、模型、币种和金额', () => {
    const form = { channelId: 'c1', upstreamModel: '', ...emptyPriceDraft() }
    applyPriceToForm(form, {
      channelId: 'c2',
      upstreamModel: 'deepseek-v4.1-flash',
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
      cachedInputPricePerMillion: '1',
    })
    assert.equal(form.channelId, 'c2')
    assert.equal(form.upstreamModel, 'deepseek-v4.1-flash')
    assert.equal(form.currency, 'CNY')
    assert.equal(form.inputPricePerMillion, '7')
    assert.equal(form.outputPricePerMillion, '14')
    assert.equal(form.cachedInputPricePerMillion, '1')
  })

  it('新增单价时清空模型与金额并恢复 USD', () => {
    const form = {
      channelId: 'c1',
      upstreamModel: 'deepseek-v4.1-flash',
      currency: 'CNY',
      inputPricePerMillion: '7',
      outputPricePerMillion: '14',
      cachedInputPricePerMillion: '1',
    }
    resetPriceForm(form)
    assert.equal(form.upstreamModel, '')
    assert.equal(form.currency, 'USD')
    assert.equal(form.inputPricePerMillion, '')
    assert.equal(form.outputPricePerMillion, '')
    assert.equal(form.cachedInputPricePerMillion, '')
  })

  it('构建模型默认价请求体并保留原币种', () => {
    const form = {
      ...defaultPriceFormState({
        modelId: 'deepseek-flash',
        currency: 'CNY',
        inputPricePerMillion: '7',
        outputPricePerMillion: '14',
        cachedInputPricePerMillion: '1',
      }),
    }
    const payload = buildDefaultPricePayload(form, ' deepseek-flash ')
    assert.equal(payload.modelId, 'deepseek-flash')
    assert.equal(payload.currency, 'CNY')
    assert.equal(payload.inputPricePerMillion, '7')
    assert.equal(payload.outputPricePerMillion, '14')
    assert.equal(payload.cachedInputPricePerMillion, '1')
  })

  it('构建批量套用请求体时去重并过滤空模型', () => {
    const payload = buildBatchPricePayload(
      { currency: 'USD', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputPricePerMillion: ' ' },
      'channel-a',
      [' m1 ', 'm1', '', null, 'm2'],
    )
    assert.deepEqual(payload.upstreamModels, ['m1', 'm2'])
    assert.equal(payload.channelId, 'channel-a')
    assert.equal(payload.cachedInputPricePerMillion, null)
  })

  it('空金额不会被当成 0，非法值返回 null', () => {
    assert.equal(parseNonNegativePrice(''), null)
    assert.equal(parseNonNegativePrice('   '), null)
    assert.equal(parseNonNegativePrice('abc'), null)
    assert.equal(parseNonNegativePrice('-1'), null)
    assert.equal(parseNonNegativePrice('0'), 0)
    assert.equal(parseNonNegativePrice('1.25'), 1.25)
    assert.equal(parseOptionalNonNegativePrice(''), null)
    assert.equal(parseOptionalNonNegativePrice('0.3'), 0.3)
  })

  it('校验 token 计费输入与输出单价必填且非负', () => {
    assert.equal(priceFormError({ pricingMode: 'token', inputPricePerMillion: '', outputPricePerMillion: '1' }), '输入和输出单价必须是非负数字')
    assert.equal(priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '' }), '输入和输出单价必须是非负数字')
    assert.equal(priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputWritePricePerMillion: '-1' }), '缓存写入单价必须是非负数字')
    assert.equal(priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputPricePerMillion: '0.5', cachedInputWritePricePerMillion: '0.8' }), null)
  })

  it('校验按次计费费用必填且非负', () => {
    assert.equal(priceFormError({ pricingMode: 'per_request', perRequestPrice: '' }), '按次费用必须是非负数字')
    assert.equal(priceFormError({ pricingMode: 'per_request', perRequestPrice: '-0.5' }), '按次费用必须是非负数字')
    assert.equal(priceFormError({ pricingMode: 'per_request', perRequestPrice: '0.04' }), null)
  })

  it('校验阶梯与总量阶梯计费档位及上限严格递增', () => {
    assert.equal(priceFormError({ pricingMode: 'tiered', priceTiers: [] }), '请添加 1 到 20 个价格档位')
    assert.equal(priceFormError({
      pricingMode: 'tiered',
      priceTiers: [
        { upToTokens: '100', inputPricePerMillion: '1', outputPricePerMillion: '2' },
        { upToTokens: '50', inputPricePerMillion: '2', outputPricePerMillion: '4' },
      ],
    }), '阶梯上限必须严格递增，最后一档留空表示不限')
    assert.equal(priceFormError({
      pricingMode: 'tiered',
      priceTiers: [
        { upToTokens: '100', inputPricePerMillion: '1', outputPricePerMillion: '2' },
        { upToTokens: '200', inputPricePerMillion: '2', outputPricePerMillion: '4' },
      ],
    }), '阶梯上限必须严格递增，最后一档留空表示不限')
    assert.equal(priceFormError({
      pricingMode: 'tiered',
      priceTiers: [
        { upToTokens: '100', inputPricePerMillion: '', outputPricePerMillion: '2' },
        { upToTokens: '', inputPricePerMillion: '2', outputPricePerMillion: '4' },
      ],
    }), '每档的输入和输出单价都必须是非负数字')
    assert.equal(priceFormError({
      pricingMode: 'tiered',
      priceTiers: [
        { upToTokens: '100', inputPricePerMillion: '1', outputPricePerMillion: '2' },
        { upToTokens: '', inputPricePerMillion: '2', outputPricePerMillion: '4' },
      ],
    }), null)
  })

  it('构建按次计费与阶梯计费请求体', () => {
    const perReqPayload = buildPricePayload({
      currency: 'USD',
      pricingMode: 'per_request',
      perRequestPrice: '0.05',
    }, 'channel-1', 'm1')
    assert.equal(perReqPayload.pricingMode, 'per_request')
    assert.equal(perReqPayload.perRequestPrice, '0.05')
    assert.equal(perReqPayload.inputPricePerMillion, null)

    const tieredPayload = buildPricePayload({
      currency: 'USD',
      pricingMode: 'tiered',
      priceTiers: [
        { upToTokens: '128000', inputPricePerMillion: '1', outputPricePerMillion: '2', cachedInputPricePerMillion: '0.5' },
        { upToTokens: '', inputPricePerMillion: '2', outputPricePerMillion: '4' },
      ],
    }, 'channel-1', 'm1')
    assert.equal(tieredPayload.pricingMode, 'tiered')
    assert.equal(tieredPayload.priceTiers.length, 2)
    assert.equal(tieredPayload.priceTiers[0].upToTokens, 128000)
    assert.equal(tieredPayload.priceTiers[0].cachedInputPricePerMillion, '0.5')
    assert.equal(tieredPayload.priceTiers[1].upToTokens, null)
  })

  it('校验定时生效时间必填且必须为未来时间', () => {
    assert.equal(
      priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2', isScheduled: true, effectiveAt: '' }),
      '请选择定时生效时间',
    )
    assert.equal(
      priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2', isScheduled: true, effectiveAt: '2020-01-01T00:00' }),
      '定时生效时间必须是未来的时间',
    )
    assert.equal(
      priceFormError({ pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2', isScheduled: true, effectiveAt: '2099-01-01T00:00' }),
      null,
    )
  })

  it('构建定时生效请求体', () => {
    const scheduledPayload = buildPricePayload({
      currency: 'USD',
      pricingMode: 'token',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      isScheduled: true,
      effectiveAt: '2099-01-01T12:00',
    }, 'channel-1', 'm1')
    assert.equal(typeof scheduledPayload.effectiveAt, 'number')
    assert.ok(scheduledPayload.effectiveAt > Date.now())

    const immediatePayload = buildPricePayload({
      currency: 'USD',
      pricingMode: 'token',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      isScheduled: false,
      effectiveAt: '2099-01-01T12:00',
    }, 'channel-1', 'm1')
    assert.equal(immediatePayload.effectiveAt, null)
  })

  it('重新打开已有计划版本时回填计划金额，避免用当前价覆盖计划', () => {
    const form = priceFormState({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      scheduledVersion: {
        effectiveFrom: Date.now() + 3_600_000,
        currency: 'USD',
        inputPricePerMillion: '3',
        outputPricePerMillion: '4',
        pricingMode: 'token',
      },
    })
    assert.equal(form.isScheduled, true)
    assert.equal(form.inputPricePerMillion, '3')
    assert.equal(form.outputPricePerMillion, '4')
  })

  it('仅在计划金额和生效时间都未修改时保留原排期', () => {
    const effectiveFrom = Date.now() + 3_600_000
    const loaded = priceFormState({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      scheduledVersion: {
        effectiveFrom,
        currency: 'USD',
        inputPricePerMillion: '3',
        outputPricePerMillion: '4',
        pricingMode: 'token',
      },
    })
    assert.equal(buildPricePayload(loaded, 'c', 'm').preserveSchedule, true)
    assert.equal(buildPricePayload({ ...loaded, inputPricePerMillion: '5' }, 'c', 'm').preserveSchedule, false)
    assert.equal(buildPricePayload({ ...loaded, isScheduled: false }, 'c', 'm').preserveSchedule, false)
  })

  it('编辑计划金额或时间时提交真实排期更新', () => {
    const effectiveFrom = Date.now() + 3_600_000
    const loaded = priceFormState({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      scheduledVersion: {
        effectiveFrom,
        currency: 'USD',
        inputPricePerMillion: '3',
        outputPricePerMillion: '4',
        pricingMode: 'token',
      },
    })
    const changedAmount = buildPricePayload({ ...loaded, inputPricePerMillion: '5' }, 'c', 'm')
    assert.equal(changedAmount.preserveSchedule, false)
    assert.equal(changedAmount.inputPricePerMillion, '5')
    const changedTime = buildPricePayload({ ...loaded, effectiveAt: '2099-01-01T00:00' }, 'c', 'm')
    assert.equal(changedTime.preserveSchedule, false)
    assert.ok(changedTime.effectiveAt > Date.now())
  })

  it('计划未设置缓存价时不会继承当前价的缓存覆盖', () => {
    const loaded = priceFormState({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      cachedInputPricePerMillion: '0.5',
      scheduledVersion: {
        effectiveFrom: Date.now() + 3_600_000,
        currency: 'USD',
        inputPricePerMillion: '3',
        outputPricePerMillion: '4',
        cachedInputPricePerMillion: null,
        pricingMode: 'token',
      },
    })
    assert.equal(loaded.cachedInputPricePerMillion, '')
    assert.equal(buildPricePayload(loaded, 'c', 'm').preserveSchedule, true)
  })

  it('带版本 ID 的计划未修改时仍保留原计划和并发校验标识', () => {
    const loaded = priceFormState({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      scheduledVersion: {
        id: 'schedule-1',
        effectiveFrom: Date.now() + 3_600_000,
        currency: 'USD',
        inputPricePerMillion: '3',
        outputPricePerMillion: '4',
        pricingMode: 'token',
      },
    })
    const payload = buildPricePayload(loaded, 'c', 'm')
    assert.equal(payload.preserveSchedule, true)
    assert.equal(payload.expectedScheduleId, 'schedule-1')
    const changed = buildPricePayload({ ...loaded, outputPricePerMillion: '9' }, 'c', 'm')
    assert.equal(changed.preserveSchedule, false)
    assert.equal(changed.expectedScheduleId, null)
  })

})
