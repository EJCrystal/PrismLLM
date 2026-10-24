import { ref } from 'vue'

export function createAdminAuditPager({ fetchPage, pageSize = 100 }) {
  const logs = ref([])
  const loading = ref(true)
  const loadingMore = ref(false)
  const error = ref('')
  const hasMore = ref(false)
  let generation = 0

  async function load() {
    const requestGeneration = ++generation
    loading.value = true
    loadingMore.value = false
    error.value = ''
    try {
      const next = await fetchPage({ limit: pageSize })
      if (requestGeneration !== generation) return false
      logs.value = Array.isArray(next) ? next : []
      hasMore.value = logs.value.length === pageSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = cause.message
      return false
    } finally {
      if (requestGeneration === generation) loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || logs.value.length === 0) return false
    const requestGeneration = generation
    const beforeId = logs.value[logs.value.length - 1].id
    loadingMore.value = true
    try {
      const next = await fetchPage({ limit: pageSize, beforeId })
      if (requestGeneration !== generation) return false
      const knownIds = new Set(logs.value.map((entry) => entry.id))
      logs.value = logs.value.concat(next.filter((entry) => !knownIds.has(entry.id)))
      hasMore.value = next.length === pageSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = cause.message
      return false
    } finally {
      if (requestGeneration === generation) loadingMore.value = false
    }
  }

  return { logs, loading, loadingMore, error, hasMore, load, loadMore }
}
