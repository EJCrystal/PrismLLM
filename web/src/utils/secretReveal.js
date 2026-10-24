import { ref } from 'vue'

export function useSecretReveal() {
  const secret = ref(null)
  const secretMode = ref('create')

  function showSecret(record, mode = 'create') {
    secret.value = record
    secretMode.value = mode
  }

  function closeSecret() {
    secret.value = null
    secretMode.value = 'create'
  }

  return { secret, secretMode, showSecret, closeSecret }
}
