import { watch, type Ref } from 'vue'

/** The owning Vue scope clears the timer when the interval changes or the view is left. */
export function useAutoRefresh(intervalMs: Ref<number>, refresh: () => void, isBusy: () => boolean): void {
  watch(intervalMs, (interval, _previous, onCleanup) => {
    if (interval <= 0) return
    const timer = setInterval(() => {
      if (!isBusy()) refresh()
    }, interval)
    onCleanup(() => clearInterval(timer))
  }, { immediate: true })
}
