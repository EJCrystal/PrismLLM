import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFile, realpath } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, extname, resolve, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const fixtureDir = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(fixtureDir, '../../..')
const cacheDir = resolve(fixtureDir, '.vite')
const port = Number(process.argv[2] || 43197)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Supply an unused port between 1024 and 65535')
const sourceFiles = new Set()
async function includeSource(file) {
  file = await realpath(file)
  if (sourceFiles.has(file)) return
  sourceFiles.add(file)
  const text = await readFile(file, 'utf8')
  for (const match of text.matchAll(/\b(?:import|export)\s+(?:[^'";]*?\s+from\s*)?['"]([^'"]+)['"]/g)) {
    if (!match[1].startsWith('.')) continue
    const target = resolve(dirname(file), match[1])
    const imported = [target, `${target}.ts`, `${target}.js`, `${target}.vue`].find((candidate) => existsSync(candidate))
    if (imported) await includeSource(imported)
  }
}
for (const path of ['components/OAuthAccountsView.vue', 'components/ToastStack.vue', 'components/Icon.vue', 'style.css']) {
  await includeSource(resolve(webDir, 'src', path))
}
const fixtureFiles = new Set(['index.html', 'main.ts', 'FixtureShell.vue', 'accounts.js', 'fake-fetch.js'].map((name) => resolve(fixtureDir, name)))
const viteEnv = resolve(webDir, 'node_modules/vite/dist/client/env.mjs')
const canonical = (path) => path.replaceAll('\\', '/').toLowerCase()
const allowedFiles = new Set([...sourceFiles, ...fixtureFiles, viteEnv].map(canonical))
const within = (file, directory) => {
  const rel = relative(directory, file)
  return rel !== '' && !rel.startsWith(`..${sep}`) && rel !== '..' && !rel.includes(':')
}
const server = await createServer({
  configFile: false,
  envFile: false,
  root: webDir,
  base: '/admin/',
  appType: 'mpa',
  publicDir: false,
  cacheDir,
  plugins: [
    {
      name: 'isolated-oauth-fixture-boundary',
      configureServer(vite) {
        vite.middlewares.use(async (req, res, next) => {
          res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'")
          res.setHeader('Cache-Control', 'no-store')
          res.setHeader('Referrer-Policy', 'no-referrer')
          const reject = (status, message) => { res.statusCode = status; res.setHeader('Content-Type', 'text/plain; charset=utf-8'); res.end(message) }
          let path
          try { path = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname) } catch { return reject(400, 'Invalid fixture path') }
          if (/^\/(?:admin\/)?api(?:\/|$)/i.test(path)) return reject(403, 'Fixture server blocks all backend API traffic; use the in-memory fake fetch')
          if (!['GET', 'HEAD'].includes(req.method)) return reject(403, 'Fixture static server is read-only')
          if (path.includes('\\') || path.includes('\0') || path.split('/').includes('..')) return reject(403, 'Blocked fixture path')
          if (!path.startsWith('/admin/')) return reject(404, 'Only the /admin/ fixture import tree is served')
          path = path.slice('/admin'.length)
          if (path === '/@vite/client' || path === '/@id/__x00__plugin-vue:export-helper') return next()
          const file = path.startsWith('/@fs/') ? resolve(path.slice('/@fs/'.length)) : resolve(webDir, `.${path}`)
          let real
          try { real = await realpath(file) } catch { return reject(404, 'Not a fixture resource') }
          const generatedDependency = within(real, resolve(cacheDir, 'deps')) && ['.js', '.map'].includes(extname(real))
          if (!allowedFiles.has(canonical(real)) && !generatedDependency) return reject(403, 'Outside the fixture import tree')
          return next()
        })
      },
    },
    vue(),
  ],
  server: {
    host: '127.0.0.1', port, strictPort: true, proxy: {}, hmr: false,
    cors: false, allowedHosts: ['127.0.0.1'],
    fs: { strict: true, allow: [fixtureDir, ...sourceFiles, viteEnv] },
  },
})
try {
  await server.listen()
  console.log(`OAUTH_FIXTURE_READY http://127.0.0.1:${port}/admin/test/fixtures/oauth-accounts/index.html`)
  console.log(`OAUTH_FIXTURE_PID ${process.pid}; Ctrl+C/SIGTERM closes the server`)
} catch (error) {
  await server.close()
  throw error
}
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await server.close(); process.exit(0) })
