import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { invalidateKeyModels, applyDiscoveredKeyModels, filterKeyModels, watchKeyCredentials } from '../src/utils/keyModels'
import { ref } from 'vue'

function draft() {
  return { models: ['A', 'B'], availableModels: [{ id: 'A' }, { id: 'B' }], manualModels: ['A', 'B'], manualModelsText: 'A', manualModelsError: '', modelsLoading: true, modelsError: '', modelsRequestId: 2, modelsSourceChanged: false }
}

describe('credential-specific model state', () => {
  it('watches only the changed credential and invalidates an in-flight request', () => {
    const keys = ref([{ ...draft(), localId: 'one', credential: '' }, { ...draft(), localId: 'two', credential: '' }])
    let changes = 0
    let hydrating = false
    const stop = watchKeyCredentials(() => keys.value, () => !hydrating, () => { changes += 1 })
    keys.value[0].credential = 'replacement'
    assert.deepEqual(keys.value[0].models, [])
    assert.equal(keys.value[0].modelsLoading, false)
    assert.equal(keys.value[0].modelsRequestId, 3)
    assert.deepEqual(keys.value[1].models, ['A', 'B'])
    keys.value.push({ ...draft(), localId: 'three', credential: '' })
    keys.value.splice(0, 1)
    assert.equal(changes, 1)
    assert.deepEqual(keys.value[0].models, ['A', 'B'])
    hydrating = true
    keys.value = [{ ...draft(), localId: 'two', credential: 'hydrated' }]
    hydrating = false
    assert.deepEqual(keys.value[0].models, ['A', 'B'])
    assert.equal(changes, 1)
    stop()
  })
  it('replaces old models after changing a credential without changing another key', () => {
    const first = draft()
    const other = draft()
    invalidateKeyModels(first)
    assert.equal(first.modelsRequestId, 3)
    assert.deepEqual(first.manualModels, [])
    applyDiscoveredKeyModels(first, [{ id: 'C', authorized: false }, { id: 'D' }])
    assert.deepEqual(first.models, ['C', 'D'])
    assert.deepEqual(other.models, ['A', 'B'])
  })
  it('retains selection and manual entries when refreshing the same credential', () => {
    const key = draft()
    key.models = ['A', 'manual', 'removed']
    key.manualModels = ['manual']
    applyDiscoveredKeyModels(key, [{ id: 'A' }, { id: 'new' }])
    assert.deepEqual(key.models, ['A', 'manual'])
    key.models = []
    applyDiscoveredKeyModels(key, [{ id: 'A', authorized: true }])
    assert.deepEqual(key.models, [])
  })
  it('filters IDs case-insensitively without changing selections', () => {
    const key = draft()
    const options = [{ id: 'GPT-4o' }, { id: 'claude' }]
    assert.deepEqual(filterKeyModels(options, ' gpt '), [{ id: 'GPT-4o' }])
    assert.deepEqual(filterKeyModels(options, 'missing'), [])
    assert.deepEqual(key.models, ['A', 'B'])
  })
})
