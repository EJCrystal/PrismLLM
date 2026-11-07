import { watch, type Ref } from 'vue'

export interface AutoRefreshOptions {
  /** Refresh immediately when the page becomes visible again, restarting the interval. */
  refreshOnVisible?: boolean
}

/** The owning Vue scope clears the timer when the interval changes or the view is left. */
export function useAutoRefresh(
  intervalMs: Ref<number>,
  refresh: () => void,
  isBusy: () => boolean,
  options: AutoRefreshOptions = {},
): void {
  watch(intervalMs, (interval, _previous, onCleanup) => {
    if (interval <= 0) return
    const runIfIdle = () => {
      if (!isBusy()) refresh()
    }
    let timer = setInterval(runIfIdle, interval)
    const onVisible = () => {
      if (document.visibilityState !== 'visible' || isBusy()) return
      // Restart the cadence so returning to the page does not trigger a second
      // request moments after the immediate foreground refresh.
      clearInterval(timer)
      timer = setInterval(runIfIdle, interval)
      refresh()
    }
    if (options.refreshOnVisible) document.addEventListener('visibilitychange', onVisible)
    onCleanup(() => {
      clearInterval(timer)
      if (options.refreshOnVisible) document.removeEventListener('visibilitychange', onVisible)
    })
  }, { immediate: true })
}
