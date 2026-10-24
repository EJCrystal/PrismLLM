/** Track the only currency conversion whose result may still be applied. */
export function createConversionRequestGuard() {
  let currentRequest = null

  return {
    start() {
      currentRequest = {}
      return currentRequest
    },
    invalidate() {
      currentRequest = null
    },
    isCurrent(request) {
      return request != null && request === currentRequest
    },
  }
}
