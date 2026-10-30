import { reactive } from 'vue'

export interface ToastItem {
  id: number
  message: string
  type: string
}

export const toasts = reactive<ToastItem[]>([])
let nextId = 1

export function notify(message: string, type = 'success'): void {
  const id = nextId++
  toasts.push({ id, message, type })
  window.setTimeout(() => {
    const index = toasts.findIndex((item) => item.id === id)
    if (index >= 0) toasts.splice(index, 1)
  }, 3600)
}
