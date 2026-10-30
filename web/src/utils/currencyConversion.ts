/** Track the only currency conversion whose result may still be applied. */
export function createConversionRequestGuard() {
  let currentRequest: object | null = null

  return {
    start() {
      currentRequest = {}
      return currentRequest
    },
    invalidate() {
      currentRequest = null
    },
    isCurrent(request: object | null): boolean {
      return request != null && request === currentRequest
    },
  }
}
