import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/' })
for (const name of ['window', 'document', 'Document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLTextAreaElement', 'SVGElement', 'Event']) globalThis[name] = dom.window[name]
const { compile, createApp, h, nextTick, ref, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default

describe('请求覆盖编辑器 extraBody', () => {
  let server, component, app, editor, value, changes
  before(async () => {
    server = await createServer({
      root: new URL('..', import.meta.url).pathname.replace(/^\/(\w:)/, '$1'),
      configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] },
      server: { middlewareMode: true, hmr: false }, appType: 'custom',
    })
    const loaded = (await server.ssrLoadModule('/src/components/RequestOverridesEditor.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/RequestOverridesEditor.vue', import.meta.url), 'utf8'))
    component = { ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), setup(props, context) { return { ...loaded.setup(props, context) } } }
  })
  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = '' })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(initial = {}, props = {}) {
    value = ref(initial)
    changes = 0
    const host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(component, {
      ...props, value: value.value, profiles: [], ref: instance => { editor = instance }, onChanged: () => { changes += 1 },
    }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await nextTick()
  }
  function field(label, selector = 'textarea') {
    const container = [...document.querySelectorAll('label.field')].find(item => item.querySelector('span')?.textContent === label)
    assert.ok(container, `缺少字段：${label}`)
    return container.querySelector(selector)
  }
  async function input(element, text, event = 'input') {
    element.value = text
    element.dispatchEvent(new Event(event, { bubbles: true }))
    await nextTick()
  }
  async function scope(id) { await input(field('配置范围', 'select'), id, 'change') }

  it('显示独立补充输入与变量提示，空配置不会预填缓存字段', async () => {
    await mount()
    assert.equal(field('请求体补充（extraBody）').value, '{}')
    assert.deepEqual(editor.getValue().extraBody, {})
    assert.deepEqual(editor.getValue().protocols, {})
    assert.match(document.body.textContent, /已有空串或 null 也保留/)
    assert.match(document.body.textContent, /!字段名强制替换整个顶层值/)
    assert.match(document.body.textContent, /所有 body 补丁完成后再应用 extraBody/)
    assert.match(document.body.textContent, /prompt_cache_key 是 body 字段，不是请求头/)
    assert.ok(document.body.textContent.includes('{"prompt_cache_key":"${sessionId}"}'))
    for (const variable of ['${sessionId}', '${threadId}', '${subagentId}']) assert.ok(document.body.textContent.includes(variable))
  })

  it('切换范围保留各自的 extraBody，摘要和保存值均包含仅补充配置', async () => {
    await mount()
    await scope('responses')
    await input(field('请求体补充（extraBody）'), '{"prompt_cache_key":"${sessionId}","!metadata":null}')
    await input(field('请求体（JSON 合并补丁）'), '{"store":null}')
    await scope('gemini')
    await input(field('请求体补充（extraBody）'), '{"generationConfig":{"temperature":0.7}}')
    const geminiChip = [...document.querySelectorAll('.request-scope-chip')].find(item => item.querySelector('span').textContent === 'Gemini')
    assert.ok(geminiChip.classList.contains('configured'))
    assert.equal(geminiChip.querySelector('small').textContent, '请求体补充')
    await scope('common')
    await input(field('请求体补充（extraBody）'), '{"tenant":"team"}')
    await scope('responses')
    assert.equal(field('请求体补充（extraBody）').value, '{"prompt_cache_key":"${sessionId}","!metadata":null}')
    const saved = editor.getValue()
    assert.deepEqual(saved.extraBody, { tenant: 'team' })
    assert.deepEqual(saved.protocols.responses.extraBody, { prompt_cache_key: '${sessionId}', '!metadata': null })
    assert.deepEqual(saved.protocols.responses.body, { store: null })
    assert.deepEqual(saved.protocols.gemini.extraBody, { generationConfig: { temperature: 0.7 } })
  })

  it('无效 extraBody 仍触发 changed 并保留快照，修复或重新加载后正确恢复', async () => {
    const saved = { inheritGlobal: false, protocols: { responses: { extraBody: { prompt_cache_key: '${sessionId}' } } } }
    await mount(saved)
    await scope('responses')
    const baseline = editor.getSnapshot()
    const before = changes
    await input(field('请求体补充（extraBody）'), '{unfinished')
    assert.ok(changes > before)
    assert.notEqual(editor.getSnapshot(), baseline)
    assert.throws(() => editor.getValue(), /Responses请求体补充（extraBody）不是有效的 JSON/)
    assert.equal(field('请求体补充（extraBody）').value, '{unfinished')
    await input(field('请求体补充（extraBody）'), JSON.stringify(saved.protocols.responses.extraBody, null, 2))
    assert.equal(editor.getSnapshot(), baseline)
    value.value = structuredClone(saved)
    await nextTick()
    assert.equal(editor.getSnapshot(), baseline)
    assert.equal(editor.getValue().inheritGlobal, false)
    value.value = {}
    await nextTick()
    assert.equal(field('请求体补充（extraBody）').value, '{}')
    assert.deepEqual(editor.getValue().protocols, {})
  })

  it('保存期间禁用补充输入', async () => {
    await mount({}, { disabled: true })
    assert.equal(field('请求体补充（extraBody）').disabled, true)
  })
})
