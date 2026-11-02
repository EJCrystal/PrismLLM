/** Run an async load and allow only the most recently started request to commit. */
export interface LatestRequestResult<T> {
  value: T
  request: number
  isLatest: boolean
}

/** A save must await a refresh that was not superseded by another list mutation. */
export async function refreshUntilCurrent(load: () => Promise<boolean>, isDisposed: () => boolean): Promise<void> {
  while (!isDisposed()) {
    if (await load()) return
  }
}

export function createLatestRequestGuard() {
  let latest = 0
  let active = 0
  return {
    async run<T>(loader: () => Promise<T>): Promise<LatestRequestResult<T>> {
      const request = ++latest
      active = request
      try {
        const value = await loader()
        return { value, request, isLatest: request === latest }
      } catch (error) {
        throw Object.assign(error instanceof Error ? error : new Error(String(error)), { request, isLatest: request === latest })
      } finally {
        if (active === request) active = 0
      }
    },
    isLatest(request: number): boolean {
      return request === latest
    },
    hasActive() {
      return active !== 0
    },
    invalidate() {
      latest += 1
    },
  }
}
