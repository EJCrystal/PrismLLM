import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { useClientKeyActions } from '../src/utils/clientKeyActions.js'
import { useSecretReveal } from '../src/utils/secretReveal.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('clientKeyActions: 调用方密钥交互状态', () => {
  it('轮换期间阻止第二次请求，成功后展示新密钥并释放锁', async () => {
    const pending = deferred()
    const calls = []
    const notifications = []
    let revealed = null
    let reloads = 0
    const actions = useClientKeyActions({
      confirmAction: () => true,
      rotateRequest: async (id) => {
        calls.push(id)
        return pending.promise
      },
      revokeRequest: async () => {},
      notify: (message, type) => notifications.push({ message, type }),
      reload: async () => { reloads += 1 },
      showSecret: (record, mode) => { revealed = { record, mode } },
    })
    const key = { id: 'key-1', name: 'search', revokedAt: null }

    const first = actions.rotate(key)
    assert.equal(actions.pendingKeyId.value, 'key-1')
    assert.equal(actions.pendingAction.value, 'rotate')
    assert.equal(await actions.rotate({ id: 'key-2', name: 'other', revokedAt: null }), false)
    assert.deepEqual(calls, ['key-1'])

    pending.resolve({ id: 'key-1', key: 'gw-new' })
    assert.equal(await first, true)
    assert.deepEqual(revealed, {
      record: { id: 'key-1', key: 'gw-new' },
      mode: 'rotate',
    })
    assert.equal(reloads, 1)
    assert.equal(notifications[0].message, '调用方密钥已轮换，旧密钥已失效')
    assert.equal(actions.pendingKeyId.value, null)
    assert.equal(actions.pendingAction.value, null)
  })

  it('取消确认时不请求 API，已撤销密钥不能轮换或撤销', async () => {
    let calls = 0
    const actions = useClientKeyActions({
      confirmAction: () => false,
      rotateRequest: async () => { calls += 1 },
      revokeRequest: async () => { calls += 1 },
      notify: () => {},
      reload: async () => {},
      showSecret: () => {},
    })

    assert.equal(await actions.rotate({ id: 'key-1', name: 'search', revokedAt: null }), false)
    assert.equal(await actions.revoke({ id: 'key-1', name: 'search', revokedAt: null }), false)
    assert.equal(await actions.rotate({ id: 'key-2', name: 'old', revokedAt: 123 }), false)
    assert.equal(await actions.revoke({ id: 'key-2', name: 'old', revokedAt: 123 }), false)
    assert.equal(calls, 0)
  })

  it('轮换失败时显示错误并释放并发锁', async () => {
    const notifications = []
    const actions = useClientKeyActions({
      confirmAction: () => true,
      rotateRequest: async () => { throw new Error('offline') },
      revokeRequest: async () => {},
      notify: (message, type) => notifications.push({ message, type }),
      reload: async () => {},
      showSecret: () => {},
    })

    assert.equal(
      await actions.rotate({ id: 'key-1', name: 'search', revokedAt: null }),
      false,
    )
    assert.deepEqual(notifications, [{ message: 'offline', type: 'error' }])
    assert.equal(actions.pendingKeyId.value, null)
    assert.equal(actions.pendingAction.value, null)
  })

  it('永久撤销成功后通知并刷新列表', async () => {
    const revoked = []
    const notifications = []
    let reloads = 0
    const actions = useClientKeyActions({
      confirmAction: () => true,
      rotateRequest: async () => {},
      revokeRequest: async (id) => revoked.push(id),
      notify: (message) => notifications.push(message),
      reload: async () => { reloads += 1 },
      showSecret: () => {},
    })

    assert.equal(
      await actions.revoke({ id: 'key-1', name: 'search', revokedAt: null }),
      true,
    )
    assert.deepEqual(revoked, ['key-1'])
    assert.deepEqual(notifications, ['调用方密钥已永久撤销'])
    assert.equal(reloads, 1)
  })
})

describe('secretReveal: 单次密钥弹窗状态', () => {
  it('展示后关闭会清空明文并恢复创建模式', () => {
    const reveal = useSecretReveal()
    reveal.showSecret({ key: 'gw-once' }, 'rotate')
    assert.deepEqual(reveal.secret.value, { key: 'gw-once' })
    assert.equal(reveal.secretMode.value, 'rotate')

    reveal.closeSecret()
    assert.equal(reveal.secret.value, null)
    assert.equal(reveal.secretMode.value, 'create')
  })
})
