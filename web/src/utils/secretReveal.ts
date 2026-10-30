import { ref } from 'vue'

import type { ClientKeyCreated } from '../types/api'

export type SecretMode = 'create' | 'rotate'

export function useSecretReveal() {
  const secret = ref<ClientKeyCreated | null>(null)
  const secretMode = ref<SecretMode>('create')

  function showSecret(record: ClientKeyCreated, mode: SecretMode = 'create'): void {
    secret.value = record
    secretMode.value = mode
  }

  function closeSecret() {
    secret.value = null
    secretMode.value = 'create'
  }

  return { secret, secretMode, showSecret, closeSecret }
}
