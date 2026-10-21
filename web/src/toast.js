import { reactive } from 'vue'

export const toasts = reactive([])
let nextId = 1

export function notify(message, type = 'success') {
  const id = nextId++
  toasts.push({ id, message, type })
  window.setTimeout(() => {
    const index = toasts.findIndex((item) => item.id === id)
    if (index >= 0) toasts.splice(index, 1)
  }, 3600)
}
