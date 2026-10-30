import { ref, shallowRef, type Ref } from 'vue'

export interface CursorPagerOptions<T> {
  fetchPage: (options: { limit: number, beforeId?: number }) => Promise<unknown>
  pageSize?: number
  getCursor?: (item: T) => number | null | undefined
  getKey?: (item: T) => unknown
}

export interface CursorPager<T> {
  items: Ref<T[], T[]>
  pageSize: Ref<number>
  loading: Ref<boolean>
  loadingMore: Ref<boolean>
  error: Ref<string>
  hasMore: Ref<boolean>
  load: () => Promise<boolean>
  loadMore: () => Promise<boolean>
  setPageSize: (size: number | string) => Promise<boolean>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

export function createCursorPager<T extends { id: number }>({
  fetchPage,
  pageSize: initialPageSize = 50,
  getCursor = (item: T) => item.id,
  getKey = getCursor,
}: CursorPagerOptions<T>): CursorPager<T> {
  const items = shallowRef<T[]>([])
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
      const page = Array.isArray(next) ? next as T[] : []
      items.value = page.slice(0, requestedSize)
      hasMore.value = page.length > requestedSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = errorMessage(cause)
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
      const normalized = Array.isArray(next) ? next as T[] : []
      const page = normalized.slice(0, requestedSize)
      const knownKeys = new Set(items.value.map(getKey))
      items.value = items.value.concat(page.filter((item) => !knownKeys.has(getKey(item))))
      hasMore.value = normalized.length > requestedSize
      return true
    } catch (cause) {
      if (requestGeneration === generation) error.value = errorMessage(cause)
      return false
    } finally {
      if (requestGeneration === generation) loadingMore.value = false
    }
  }

  async function setPageSize(nextSize: number | string) {
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
