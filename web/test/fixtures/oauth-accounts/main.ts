import { installOAuthFixtureFetch } from './fake-fetch.js'

// Install before importing or mounting production components. No App/auth bootstrap.
installOAuthFixtureFetch(window)
document.documentElement.dataset.theme = new URLSearchParams(window.location.search).get('theme') === 'dark' ? 'dark' : 'light'

const [{ createApp }, { default: FixtureShell }] = await Promise.all([
  import('vue'),
  import('./FixtureShell.vue'),
  import('../../../src/style.css'),
])
createApp(FixtureShell).mount('#app')
