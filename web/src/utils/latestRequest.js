/** Run an async load and allow only the most recently started request to commit. */
export function createLatestRequestGuard() {
  let latest = 0
  return {
    async run(loader) {
      const request = ++latest
      try {
        const value = await loader()
        return { value, request, isLatest: request === latest }
      } catch (error) {
        throw Object.assign(error, { request, isLatest: request === latest })
      }
    },
    isLatest(request) {
      return request === latest
    },
    invalidate() {
      latest += 1
    },
  }
}
