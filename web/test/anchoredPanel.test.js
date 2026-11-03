import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { bindVisualViewport } from '../src/composables/useAnchoredPanel'

describe('anchored panel viewport listeners', () => {
  it('repositions on visual viewport resize and scroll and removes both listeners', () => {
    const viewport = new EventTarget()
    let positions = 0
    const remove = bindVisualViewport(viewport, () => { positions++ })
    viewport.dispatchEvent(new Event('resize'))
    viewport.dispatchEvent(new Event('scroll'))
    assert.equal(positions, 2)
    remove()
    viewport.dispatchEvent(new Event('resize'))
    viewport.dispatchEvent(new Event('scroll'))
    assert.equal(positions, 2)
  })

  it('supports browsers without visualViewport', () => {
    assert.doesNotThrow(() => bindVisualViewport(null, () => {})())
  })
})
