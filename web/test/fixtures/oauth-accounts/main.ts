import { installOAuthFixtureFetch } from './fake-fetch.js'

// Install before importing or mounting production components. No App/auth bootstrap.
const mock = installOAuthFixtureFetch(window)
const params = new URLSearchParams(window.location.search)
const scenario = params.get('scenario')
if (scenario === 'candidate-failure') mock.fixture.controls.failSuggestions = true
if (scenario === 'mapping-failure') mock.fixture.controls.failMappingReads = 1
if (scenario === 'conflict') mock.fixture.controls.conflictNextSave = true
if (scenario === 'saved-refresh-failure') mock.fixture.controls.failMappingAfterSave = true
if (scenario === 'sync-conflict') mock.fixture.controls.syncConflict = { upstreamModel: 'gemini-3-pro', canonicalModelId: 'other-account/sync-public-id' }
if (scenario === 'unknown') {
  const account = mock.fixture.accounts.find(item => item.id === 'fixture-ag-grouped')
  account.models = ['gemini-3-pro', 'custom-upstream']
}
if (scenario === 'sync-draft') {
  mock.fixture.syncCatalogs['fixture-ag-grouped'] = ['gemini-3-pro', 'gemini-new-sync-fixture']
  mock.fixture.automaticMappings['gemini-3-pro'] = 'google/updated-sync-pro'
}
document.documentElement.dataset.theme = params.get('theme') === 'dark' ? 'dark' : 'light'

const [{ createApp }, { default: FixtureShell }] = await Promise.all([
  import('vue'),
  import('./FixtureShell.vue'),
  import('../../../src/style.css'),
])
createApp(FixtureShell).mount('#app')
