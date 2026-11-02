import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/** Keep teleported controls inside the viewport, including horizontally scrolled tables. */
export function useAnchoredPanel(width = 280, estimatedHeight = 240) {
  const anchor = ref<HTMLElement | null>(null)
  const panel = ref<HTMLElement | null>(null)
  const panelStyle = ref<Record<string, string>>({ visibility: 'hidden' })

  function position() {
    if (!anchor.value) return
    const rect = anchor.value.getBoundingClientRect()
    const viewport = window.visualViewport
    const viewportWidth = viewport?.width ?? window.innerWidth
    const viewportHeight = viewport?.height ?? window.innerHeight
    const actualWidth = Math.min(width, viewportWidth - 24)
    const height = Math.min(panel.value?.offsetHeight || estimatedHeight, viewportHeight - 32)
    const left = Math.max(12, Math.min(rect.right - actualWidth, viewportWidth - actualWidth - 12))
    const below = rect.bottom + 8
    const top = below + height <= viewportHeight - 12
      ? below
      : Math.max(12, rect.top - height - 8)
    panelStyle.value = { position: 'fixed', width: `${actualWidth}px`, left: `${left}px`, top: `${top}px`, maxHeight: `${viewportHeight - top - 12}px` }
  }

  async function place() {
    position()
    await nextTick()
    position()
  }
  function repositionOpenPanel() {
    if (anchor.value?.getAttribute('aria-expanded') === 'true') position()
  }

  onMounted(() => {
    window.addEventListener('resize', repositionOpenPanel)
    window.addEventListener('scroll', repositionOpenPanel, true)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', repositionOpenPanel)
    window.removeEventListener('scroll', repositionOpenPanel, true)
  })
  return { anchor, panel, panelStyle, place }
}
