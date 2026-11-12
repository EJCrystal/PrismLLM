import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, h, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { JSDOM } from 'jsdom'

describe('channel test workspace', () => {
  let server
  let workbench
  let emptyState
  let drawer
  let searchSelect
  const panels = new Map()
  const channel = { id: 'workspace-test', name: '测试渠道', enabled: true }
  const target = {
    channelId: channel.id, channelName: channel.name, channelEnabled: true,
    model: 'test-model', protocols: ['chat_completions'],
    credentials: [{ id: 'credential-test', name: '测试凭据', priority: 1 }],
  }

  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    workbench = (await server.ssrLoadModule('/src/components/TestWorkbench.vue')).default
    emptyState = (await server.ssrLoadModule('/src/components/TestEmptyState.vue')).default
    drawer = (await server.ssrLoadModule('/src/components/ChannelTestDrawer.vue')).default
    searchSelect = (await server.ssrLoadModule('/src/components/SearchSelect.vue')).default
    for (const name of ['ChannelConnectivityPanel', 'IqTestView', 'FingerprintTestView']) {
      panels.set(name, (await server.ssrLoadModule('/src/components/' + name + '.vue')).default)
    }
  })
  after(async () => { await server?.close() })

  const renderWorkbench = async (props = {}) => {
    let state
    const wrapped = {
      ...workbench,
      setup(properties, context) {
        state = workbench.setup(properties, context)
        return state
      },
    }
    const app = createSSRApp({
      render: () => h(wrapped, props, {
        configuration: () => h('label', { id: 'configuration' }, '配置字段'),
        actions: () => h('button', { id: 'run' }, '开始测试'),
        default: () => h(emptyState, { icon: 'bolt', title: '尚未运行', description: '配置后开始测试' }),
      }),
    })
    const document = new JSDOM(await renderToString(app)).window.document
    return { document, state }
  }

  const renderPanel = async (name, configure = () => {}) => {
    const component = panels.get(name)
    const wrapped = {
      ...component,
      setup(props, context) {
        const state = component.setup(props, context)
        state.loading.value = false
        state.targets.value = [target]
        if (name === 'IqTestView') {
          state.benchmarks.value = [{ id: 'pelican', label: '鹈鹕', prompt: '默认提示词', grading: '本地规则', durationHint: '通常需要 30 秒', effectiveTimeoutMs: 180000, maxTimeoutMs: 600000 }]
        }
        configure(state)
        return state
      },
    }
    return new JSDOM(await renderToString(createSSRApp(wrapped, { channel }))).window.document
  }

  it('keeps the run action outside the independently scrolling fields', async () => {
    const { document } = await renderWorkbench()
    const fields = document.querySelector('.test-workbench-fields')
    const actions = document.querySelector('.test-workbench-actions')
    assert.ok(fields.querySelector('#configuration'))
    assert.equal(fields.querySelector('#run'), null)
    assert.ok(actions.querySelector('#run'))
    assert.equal(document.querySelectorAll('#run').length, 1)
    assert.ok(document.querySelector('.test-workbench-results .test-empty-state'))
    assert.equal(fields.getAttribute('tabindex'), '0')
    assert.equal(document.querySelector('.test-workbench-results').getAttribute('tabindex'), '0')
  })

  it('opens results on submission without moving focus or scrolling the entire dialog', async () => {
    const { state, document } = await renderWorkbench()
    const scrollCalls = []
    state.results.value = { scrollTo: (options) => scrollCalls.push(options) }
    assert.equal(state.activePane.value, 'configuration')
    assert.equal(document.querySelector('button[aria-pressed="true"]').textContent, '配置')
    state.showResults()
    await nextTick()
    assert.equal(state.activePane.value, 'results')
    assert.deepEqual(scrollCalls, [{ top: 0 }])
    state.activePane.value = 'configuration'
    state.scrollResults()
    await nextTick()
    assert.equal(state.activePane.value, 'configuration')
    assert.equal(scrollCalls.length, 2)
  })

  it('does not impose configuration, actions, or nested scrolling on historical records', async () => {
    const { document } = await renderWorkbench({ recordMode: true })
    assert.ok(document.querySelector('.test-record-workspace'))
    assert.ok(document.querySelector('.test-empty-state'))
    assert.equal(document.querySelector('.test-workbench'), null)
    assert.equal(document.querySelector('#configuration'), null)
    assert.equal(document.querySelector('#run'), null)
  })

  it('fits the workspace above the software keyboard on narrow and wide viewports', async () => {
    const { state } = await renderWorkbench()
    const originalWindow = globalThis.window
    try {
      globalThis.window = { innerWidth: 390, visualViewport: { height: 500, offsetTop: 0 } }
      state.workspace.value = { isConnected: true, getBoundingClientRect: () => ({ top: 200 }) }
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, '284px')
      globalThis.window.visualViewport.offsetTop = 40
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, '324px')
      for (const width of [1024, 1366]) {
        globalThis.window.innerWidth = width
        state.fitVisibleViewport()
        assert.equal(state.availableHeight.value, '324px')
      }
      globalThis.window.visualViewport.height = 768
      globalThis.window.visualViewport.offsetTop = 0
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, '552px')
    } finally {
      if (originalWindow === undefined) delete globalThis.window
      else globalThis.window = originalWindow
    }
  })

  it('clears the viewport constraint when the viewport or connected workspace is unavailable', async () => {
    const { state } = await renderWorkbench()
    const originalWindow = globalThis.window
    try {
      globalThis.window = { visualViewport: { height: 500, offsetTop: 0 } }
      state.workspace.value = { isConnected: true, getBoundingClientRect: () => ({ top: 200 }) }
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, '284px')
      state.workspace.value.isConnected = false
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, undefined)
      state.workspace.value.isConnected = true
      delete globalThis.window.visualViewport
      state.fitVisibleViewport()
      assert.equal(state.availableHeight.value, undefined)
    } finally {
      if (originalWindow === undefined) delete globalThis.window
      else globalThis.window = originalWindow
    }
  })

  for (const [name, title] of [
    ['ChannelConnectivityPanel', '尚未运行测试'],
    ['IqTestView', '选择模型，运行第一道题'],
    ['FingerprintTestView', '还没有检测结果'],
  ]) {
    it(name + ' uses the shared empty state and a single persistent run action', async () => {
      const component = panels.get(name)
      const wrapped = {
        ...component,
        setup(props, context) {
          const state = component.setup(props, context)
          state.loading.value = false
          state.targets.value = [target]
          return state
        },
      }
      const document = new JSDOM(await renderToString(createSSRApp(wrapped, { channel }))).window.document
      assert.equal(document.querySelector('.test-empty-state h3').textContent, title)
      assert.equal(document.querySelectorAll('.test-workbench-actions .button.primary').length, 1)
      assert.equal(document.querySelector('.test-workbench-fields .button.primary'), null)
      assert.ok(document.querySelector('.test-empty-icon'))
      assert.equal(document.querySelectorAll('.test-empty-steps li').length, 3)
      assert.ok(document.querySelector('.test-form-note'))
      assert.ok(document.querySelector('.test-form-section-title'))
      assert.equal(document.querySelector('.connectivity-channel'), null)
      const note = document.querySelector('.test-workbench-actions .test-action-note')
      assert.match(note.textContent, /真实上游请求，可能产生费用/)
      assert.equal(note.closest('details'), null)
    })
  }

  for (const name of ['IqTestView', 'FingerprintTestView']) {
    it(name + ' preserves the read-only record layout', async () => {
      const component = panels.get(name)
      const wrapped = {
        ...component,
        setup(props, context) {
          const state = component.setup(props, context)
          state.loading.value = false
          return state
        },
      }
      const document = new JSDOM(await renderToString(createSSRApp(wrapped, { mode: 'record' }))).window.document
      assert.ok(document.querySelector('.test-record-workspace'))
      assert.equal(document.querySelector('.test-workbench-actions'), null)
      assert.equal(document.querySelector('.test-workbench-fields'), null)
      assert.equal(document.querySelector('.test-empty-state'), null)
    })
  }

  it('uses the same empty canvas when no model has a usable credential', async () => {
    const component = panels.get('ChannelConnectivityPanel')
    const wrapped = {
      ...component,
      setup(props, context) {
        const state = component.setup(props, context)
        state.loading.value = false
        return state
      },
    }
    const document = new JSDOM(await renderToString(createSSRApp(wrapped, { channel }))).window.document
    assert.equal(document.querySelector('.test-empty-state h3').textContent, '没有可测试的模型')
    assert.equal(document.querySelector('.test-workbench-actions'), null)
  })

  it('keeps connectivity timing and reset visible while collapsing request details', async () => {
    const document = await renderPanel('ChannelConnectivityPanel', (state) => {
      state.prompt.value = 'Hello'
    })
    const meta = document.querySelector('.test-form-meta')
    assert.match(meta.textContent, /5 字符 · 单次超时 45 秒/)
    assert.equal(meta.closest('details'), null)
    assert.equal(meta.querySelector('button').textContent, '恢复默认')
    const help = document.querySelector('details.test-form-help')
    assert.equal(help.open, false)
    assert.equal(help.querySelector('summary').textContent, '请求限制与记录说明')
    assert.match(help.textContent, /128 个输出 token.*不写入请求日志/)
  })

  it('collapses candy grading rules without hiding question availability', async () => {
    const document = await renderPanel('IqTestView', (state) => {
      state.selectedBenchmark.value = 'candy'
      state.questions.value = [{ enabled: true }, { enabled: false }]
    })
    const editor = document.querySelector('.iq-prompt-editor')
    assert.match(editor.querySelector('.iq-grading').textContent, /已启用 1 \/ 2 道题，每次随机抽一道/)
    const help = editor.querySelector('details')
    assert.equal(help.open, false)
    assert.equal(help.querySelector('summary').textContent, '查看判分规则')
    assert.match(help.textContent, /标准答案不会发送给模型/)
  })

  it('keeps the effective timeout visible while grouping defaults and request policy', async () => {
    const document = await renderPanel('IqTestView', (state) => {
      state.timeoutSeconds.value = 240
    })
    const note = document.querySelector('.iq-run-note')
    assert.equal(note.closest('details'), null)
    assert.match(note.textContent, /30 秒.*240 秒/)
    const help = [...document.querySelectorAll('details')].find((node) => node.querySelector('summary')?.textContent === '超时与请求说明')
    assert.equal(help.open, false)
    assert.match(help.textContent, /默认 180 秒.*上限\s+600 秒/)
    assert.match(help.textContent, /不写入请求日志，也不消耗调用方密钥额度/)
  })

  for (const [id, expected] of [['manxue', /manxue.ai/], ['vision', /6 帧截图/], ['custom', /请确认插件来源可信/]]) {
    it('keeps ' + id + ' privacy warnings outside collapsed judge documentation', async () => {
      const document = await renderPanel('IqTestView', (state) => {
        state.judges.value = [{ id, label: '测试判分器', builtIn: id !== 'custom', description: '详细判分器介绍' }]
        state.selectedJudgeId.value = id
      })
      const warning = document.querySelector('.iq-privacy-warning')
      assert.match(warning.textContent, expected)
      assert.equal(warning.closest('details'), null)
      const help = [...document.querySelectorAll('details')].find((node) => node.textContent.includes('详细判分器介绍'))
      assert.equal(help.open, false)
      assert.equal(help.querySelector('summary').textContent, '查看判分器说明')
    })
  }

  for (const hasInfo of [true, false]) {
    it('keeps fingerprint limits and timing visible ' + (hasInfo ? 'with' : 'without') + ' reference metadata', async () => {
      const document = await renderPanel('FingerprintTestView', (state) => {
        if (hasInfo) state.detectorInfo.value = { modelCount: 42 }
      })
      assert.match(document.querySelector('.test-form-note').textContent, /不能确认真实模型来源/)
      const timing = document.querySelector('.fingerprint-run-note')
      assert.match(timing.textContent, /30–120 秒.*4 分钟/)
      assert.equal(timing.closest('details'), null)
      const help = document.querySelector('details.test-form-help')
      assert.equal(help.open, false)
      assert.equal(help.querySelector('summary').textContent, '参考库与结果说明')
      assert.match(help.textContent, /不应用作封禁或计费的唯一依据/)
      assert.equal(help.textContent.includes('42 个模型'), hasInfo)
      assert.equal(document.querySelector('.fingerprint-disclaimer'), null)
    })
  }

  it('constrains desktop scroll regions and keeps a single mobile pane with safe-area actions', () => {
    const source = readFileSync(new URL('../src/components/TestWorkbench.vue', import.meta.url), 'utf8')
    assert.match(source, /grid-template-rows: minmax\(0, 1fr\)/)
    assert.match(source, /min-height: 0;[\s\S]*?overflow-y: auto;/)
    assert.match(source, /@media \(max-width: 1000px\)/)
    assert.match(source, /\.test-workbench-results,[\s\S]*?\.show-results \.test-workbench-fields \{\s*display: none;/)
    assert.match(source, /env\(safe-area-inset-bottom\)/)
    assert.doesNotMatch(source, /position:\s*fixed/)
    const styles = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
    assert.match(styles, /body:has\(\.modal-body\.is-contained\) \.toast-stack \{\s*top: calc\(12px \+ env\(safe-area-inset-top\)\);\s*bottom: auto;/)
  })

  it('supports arrow, Home and End navigation without resetting a selected tab or refreshing records twice', async () => {
    let state
    const wrapped = {
      ...drawer,
      ssrRender: undefined,
      render: () => null,
      setup(props, context) {
        state = drawer.setup(props, context)
        return state
      },
    }
    await renderToString(createSSRApp(wrapped, { channel }))
    const focused = []
    state.tabList.value = { querySelectorAll: () => state.tabs.map((tab) => ({ focus: () => focused.push(tab.id) })) }
    let prevented = 0
    const press = (key, index) => state.navigateTabs({ key, preventDefault: () => prevented++ }, index)
    await press('ArrowLeft', 0)
    assert.equal(state.activeTab.value, 'records')
    assert.equal(state.recordsKey.value, 1)
    await press('End', 3)
    assert.equal(state.recordsKey.value, 1)
    await press('ArrowRight', 3)
    assert.equal(state.activeTab.value, 'connectivity')
    await press('ArrowRight', 0)
    assert.equal(state.activeTab.value, 'iq')
    state.openRecord(42)
    assert.deepEqual(state.recordTarget.value, { type: 'iq', id: 42 })
    await press('Home', 3)
    await press('Enter', 0)
    assert.equal(state.activeTab.value, 'connectivity')
    assert.equal(prevented, 5)
    assert.deepEqual(focused, ['records', 'records', 'connectivity', 'iq', 'connectivity'])
  })

  it('keeps status next to the global title and short accessible tabs on every viewport', () => {
    const source = readFileSync(new URL('../src/components/ChannelTestDrawer.vue', import.meta.url), 'utf8')
    const modal = readFileSync(new URL('../src/components/Modal.vue', import.meta.url), 'utf8')
    assert.match(source, /#title-extra/)
    assert.match(source, /渠道路由已停用，仍可手动测试/)
    assert.match(source, /:aria-controls=/)
    assert.match(source, /role="tabpanel" :aria-labelledby=/)
    assert.match(source, /:tabindex="activeTab === tab.id \? 0 : -1"/)
    assert.match(source, /grid-template-columns: repeat\(4, minmax\(0, 1fr\)\)/)
    assert.doesNotMatch(source, /tab\.summary|<small>/)
    assert.match(modal, /<DialogTitle[\s\S]*?<slot name="title-extra"/)
    assert.match(modal, /\.modal-title-row \{[\s\S]*?flex-wrap: wrap;/)
  })

  for (const compact of [false, true]) {
    it('retains full selected details in ' + (compact ? 'single-line' : 'default') + ' searchable selects', async () => {
      const props = {
        label: '上游凭据', modelValue: 'key-1', compact,
        options: [{ value: 'key-1', label: '很长的测试凭据名称', group: '演示渠道', hint: '优先级 1' }],
      }
      const document = new JSDOM(await renderToString(createSSRApp(searchSelect, props))).window.document
      const trigger = document.querySelector('.search-select-trigger')
      assert.equal(trigger.classList.contains('is-compact'), compact)
      assert.equal(trigger.querySelector('strong').textContent, '很长的测试凭据名称')
      assert.equal(trigger.querySelector('small').textContent, '演示渠道 · 优先级 1')
      assert.match(trigger.getAttribute('aria-label'), /很长的测试凭据名称.*演示渠道.*优先级 1/)
      if (compact) assert.match(trigger.getAttribute('title'), /优先级 1/)
    })
  }

  for (const expanded of [false, true]) {
    it('keeps the editable prompt and reset action when ' + (expanded ? 'expanded' : 'collapsed'), async () => {
      const component = panels.get('IqTestView')
      const wrapped = {
        ...component,
        setup(props, context) {
          const state = component.setup(props, context)
          state.loading.value = false
          state.benchmarks.value = [{ id: 'pelican', label: '鹈鹕', prompt: '默认提示词', grading: '本地规则', durationHint: '30秒', effectiveTimeoutMs: 180000, maxTimeoutMs: 600000 }]
          state.promptText.value = '用户修改的提示词'
          state.promptExpanded.value = expanded
          return state
        },
      }
      const document = new JSDOM(await renderToString(createSSRApp(wrapped, { channel }))).window.document
      const input = document.querySelector('.iq-prompt-input')
      const toggle = document.querySelector('.iq-prompt-toggle')
      assert.equal(input.getAttribute('rows'), expanded ? '10' : '4')
      assert.equal(input.textContent, '用户修改的提示词')
      assert.equal(toggle.getAttribute('aria-expanded'), String(expanded))
      assert.equal(toggle.getAttribute('aria-controls'), input.id)
      assert.match(document.querySelector('.iq-prompt-editor').textContent, /恢复默认/)
      assert.match(document.querySelector('.test-form-help').textContent, /查看判分规则.*本地规则/)
    })
  }

  for (const name of ['ChannelConnectivityPanel', 'FingerprintTestView']) {
    it(name + ' keeps manual testing available for a disabled channel', async () => {
      const component = panels.get(name)
      const wrapped = {
        ...component,
        setup(props, context) {
          const state = component.setup(props, context)
          state.loading.value = false
          state.targets.value = [{ ...target, channelEnabled: false }]
          if (name === 'ChannelConnectivityPanel') {
            state.selectedModel.value = target.model
            state.defaultPrompt.value = 'Hello'
            state.prompt.value = 'Hello'
            state.syncSelection()
          } else {
            state.selectedTargetKey.value = state.modelTestTargetKey(channel.id, target.model)
            state.syncSelectedTarget()
          }
          return state
        },
      }
      const document = new JSDOM(await renderToString(createSSRApp(wrapped, { channel: { ...channel, enabled: false } }))).window.document
      assert.match(document.querySelector('.test-form').textContent, /渠道已停用，仍可用已保存的凭据发起测试/)
      assert.equal(document.querySelector('.test-workbench-actions .button.primary').disabled, false)
    })
  }
})
