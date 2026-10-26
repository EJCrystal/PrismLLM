/** Run an async load and allow only the most recently started request to commit. */
export function createLatestRequestGuard() {
  let latest = 0
  let active = 0
  return {
    async run(loader) {
      const request = ++latest
      active = request
      try {
        const value = await loader()
        return { value, request, isLatest: request === latest }
      } catch (error) {
        throw Object.assign(error, { request, isLatest: request === latest })
      } finally {
        if (active === request) active = 0
      }
    },
    isLatest(request) {
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
