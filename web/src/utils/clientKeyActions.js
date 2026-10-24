import { ref } from 'vue'

export function useClientKeyActions({
  confirmAction,
  rotateRequest,
  revokeRequest,
  notify,
  reload,
  showSecret,
}) {
  const pendingKeyId = ref(null)
  const pendingAction = ref(null)

  async function rotate(key) {
    if (pendingKeyId.value != null || key.revokedAt != null) return false
    if (!confirmAction('确定轮换调用方密钥「' + key.name + '」？旧密钥会立即失效。')) return false
    pendingKeyId.value = key.id
    pendingAction.value = 'rotate'
    try {
      const rotated = await rotateRequest(key.id)
      showSecret(rotated, 'rotate')
      notify('调用方密钥已轮换，旧密钥已失效')
      await reload()
      return true
    } catch (cause) {
      notify(cause.message, 'error')
      return false
    } finally {
      pendingKeyId.value = null
      pendingAction.value = null
    }
  }

  async function revoke(key) {
    if (pendingKeyId.value != null || key.revokedAt != null) return false
    if (!confirmAction('确定永久撤销调用方密钥「' + key.name + '」？撤销后不能重新启用或轮换。')) return false
    pendingKeyId.value = key.id
    pendingAction.value = 'revoke'
    try {
      await revokeRequest(key.id)
      notify('调用方密钥已永久撤销')
      await reload()
      return true
    } catch (cause) {
      notify(cause.message, 'error')
      return false
    } finally {
      pendingKeyId.value = null
      pendingAction.value = null
    }
  }

  return { pendingKeyId, pendingAction, rotate, revoke }
}
