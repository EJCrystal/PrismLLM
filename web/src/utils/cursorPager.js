import { ref } from 'vue'

export function createCursorPager({
  fetchPage,
  pageSize: initialPageSize = 50,
  getCursor = (item) => item.id,
  getKey = getCursor,
}) {
  const items = ref([])
  const pageSize = ref(initialPageSize)
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
      const requestedSize = pageSize.value
      const next = await fetchPage({ limit: requestedSize + 1 })
      if (requestGeneration !== generation) return false
      const page = Array.isArray(next) ? next : []
      items.value = page.slice(0, requestedSize)
      hasMore.value = page.length > requestedSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = cause.message
      return false
    } finally {
      if (requestGeneration === generation) loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || !hasMore.value || items.value.length === 0) {
      return false
    }
    const cursor = getCursor(items.value[items.value.length - 1])
    if (cursor == null) {
      hasMore.value = false
      return false
    }

    const requestGeneration = generation
    const requestedSize = pageSize.value
    loadingMore.value = true
    try {
      const next = await fetchPage({ limit: requestedSize + 1, beforeId: cursor })
      if (requestGeneration !== generation) return false
      const page = next.slice(0, requestedSize)
      const knownKeys = new Set(items.value.map(getKey))
      items.value = items.value.concat(page.filter((item) => !knownKeys.has(getKey(item))))
      hasMore.value = next.length > requestedSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = cause.message
      return false
    } finally {
      if (requestGeneration === generation) loadingMore.value = false
    }
  }

  async function setPageSize(nextSize) {
    const normalized = Number(nextSize)
    if (!Number.isInteger(normalized) || normalized <= 0 || normalized === pageSize.value) {
      return false
    }
    pageSize.value = normalized
    items.value = []
    hasMore.value = false
    return load()
  }

  return { items, pageSize, loading, loadingMore, error, hasMore, load, loadMore, setPageSize }
}
