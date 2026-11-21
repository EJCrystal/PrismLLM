import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'
import { pluginContributionVersionKey, recheckPluginContributions } from '../src/utils/pluginDiscovery'
import { makePluginFixture } from './fixtures/plugin.js'

describe('request-local plugin contribution rechecks', () => {
  it('reads one state per plugin for all its entries and does not retain it across refreshes', async t => {
    const first = makePluginFixture({ id: 'first.plugin' })
    const second = makePluginFixture({ id: 'second.plugin' })
    const entries = Array.from({ length: 32 }, (_, index) => ({
      ...first.contribution, entry: { ...first.contribution.entry, id: `entry-${index}` },
    }))
    const available = [...entries, second.contribution]
    const invalid = new Set(available.map(pluginContributionVersionKey))
    const reads = []
    const old = api.getPlugin
    t.after(() => { api.getPlugin = old })
    api.getPlugin = async id => {
      reads.push(id)
      return id === first.manifest.id ? { ...first.state, activeUi: { schemaVersion: 1, entries: entries.map(item => item.entry) } } : second.state
    }
    const signal = new AbortController().signal
    assert.equal((await recheckPluginContributions(available, invalid, signal)).recovered.length, 33)
    assert.deepEqual(reads, [first.manifest.id, second.manifest.id])
    assert.equal((await recheckPluginContributions(available, invalid, signal)).recovered.length, 33)
    assert.deepEqual(reads, [first.manifest.id, second.manifest.id, first.manifest.id, second.manifest.id])
  })

  it('isolates a failed grouped read without repeating it or stopping another plugin', async t => {
    const first = makePluginFixture({ id: 'failed.plugin' })
    const second = makePluginFixture({ id: 'healthy.plugin' })
    const available = [...first.contributions, ...second.contributions]
    const old = api.getPlugin
    t.after(() => { api.getPlugin = old })
    const reads = []
    api.getPlugin = async id => { reads.push(id); if (id === first.manifest.id) throw Error('single failed read'); return second.state }
    const result = await recheckPluginContributions(available, new Set(available.map(pluginContributionVersionKey)), new AbortController().signal)
    assert.equal(result.error, 'single failed read')
    assert.deepEqual(result.recovered, second.contributions.map(pluginContributionVersionKey))
    assert.deepEqual(reads, [first.manifest.id, second.manifest.id])
  })

  it('bounds parallel grouped reads so one stalled plugin cannot block other recoveries', async t => {
    const stalled = makePluginFixture({ id: 'stalled.plugin' })
    const healthy = makePluginFixture({ id: 'healthy.plugin' })
    const later = makePluginFixture({ id: 'later.plugin' })
    const available = [...stalled.contributions, ...healthy.contributions, ...later.contributions]
    const invalid = new Set(available.map(pluginContributionVersionKey))
    const reads = []
    let stalledSignal
    const old = api.getPlugin
    t.after(() => { api.getPlugin = old })
    api.getPlugin = (id, options = {}) => {
      reads.push(id)
      if (id === stalled.manifest.id) {
        stalledSignal = options.signal
        return new Promise(() => {})
      }
      if (id === healthy.manifest.id) return Promise.resolve(healthy.state)
      return Promise.resolve(later.state)
    }

    const result = await recheckPluginContributions(available, invalid, new AbortController().signal, { timeoutMs: 20, concurrency: 2 })
    assert.equal(result.error, '插件状态复核超时')
    assert.deepEqual(new Set(result.recovered), new Set([
      ...healthy.contributions.map(pluginContributionVersionKey),
      ...later.contributions.map(pluginContributionVersionKey),
    ]))
    assert.deepEqual(reads.sort(), [stalled.manifest.id, healthy.manifest.id, later.manifest.id].sort())
    assert.equal(stalledSignal.aborted, true)
  })
})
