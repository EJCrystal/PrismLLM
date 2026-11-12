import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { JSDOM } from 'jsdom'

describe('channel model preview', () => {
  let server
  let component
  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/ChannelModelPreview.vue')).default
  })
  after(async () => { await server?.close() })
  const items = [
    { displayName: 'gpt-6.1-sol', title: 'gpt-6.1-sol；默认价', priceSource: 'default', priceLabel: '默认价' },
    { displayName: 'a-very-long-provider-model-name', title: '完整上游模型名称；渠道价', priceSource: 'channel', priceLabel: '渠道价' },
    { displayName: 'deepseek-flash', title: 'deepseek-flash；未定价', priceSource: 'none', priceLabel: '未定价' },
  ]
  const render = async (props = {}) => {
    const html = await renderToString(createSSRApp(component, { items, expanded: false, listId: 'channel-models-test', ...props }))
    return new JSDOM(html).window.document
  }

  it('shows at most two grouped models and an accessible remainder button', async () => {
    const document = await render()
    const groups = document.querySelectorAll('.channel-model-entry')
    assert.equal(groups.length, 2)
    assert.equal(groups[0].querySelector('.channel-model-name').textContent, 'gpt-6.1-sol')
    assert.equal(groups[0].querySelector('.channel-model-price-source').textContent, '默认价')
    assert.equal(groups[1].title, '完整上游模型名称；渠道价')
    const button = document.querySelector('button')
    assert.equal(button.textContent.trim(), '+1')
    assert.equal(button.getAttribute('aria-expanded'), 'false')
    assert.equal(button.getAttribute('aria-controls'), 'channel-models-test')
    assert.equal(document.querySelector('#channel-models-test').textContent.includes('deepseek-flash'), false)
  })

  it('expands all models without losing price associations and can be collapsed', async () => {
    const document = await render({ expanded: true })
    assert.equal(document.querySelectorAll('.channel-model-entry').length, 3)
    assert.equal(document.querySelector('.channel-model-price-flag').textContent, '未定价')
    assert.equal(document.querySelector('button').textContent.trim(), '收起')
    assert.equal(document.querySelector('button').getAttribute('aria-expanded'), 'true')
    assert.ok(document.querySelector('.is-expanded'))
  })

  it('does not render an expansion control when all models already fit', async () => {
    const document = await render({ items: items.slice(0, 2) })
    assert.equal(document.querySelector('button'), null)
    assert.equal(document.querySelectorAll('.channel-model-entry').length, 2)
  })
})
