import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

type ViewportEvents = Pick<VisualViewport, 'addEventListener' | 'removeEventListener'>

export function bindVisualViewport(viewport: ViewportEvents | null, handler: EventListener): () => void {
  if (!viewport) return () => {}
  viewport.addEventListener('resize', handler)
  viewport.addEventListener('scroll', handler)
  return () => {
    viewport.removeEventListener('resize', handler)
    viewport.removeEventListener('scroll', handler)
  }
}

interface AnchoredPanelOptions {
  /**
   * 优先向下展开，空间不足时压缩高度并内部滚动，而不是整体翻到锚点上方。
   * 用于紧跟在其他表单字段之后的下拉，翻上去会盖住前面的字段。
   */
  preferBelow?: boolean
}

/** Keep teleported controls inside the viewport, including horizontally scrolled tables. */
export function useAnchoredPanel(width = 280, estimatedHeight = 240, options: AnchoredPanelOptions = {}) {
  const anchor = ref<HTMLElement | null>(null)
  const panel = ref<HTMLElement | null>(null)
  const panelStyle = ref<Record<string, string>>({ visibility: 'hidden' })
  let unbindVisualViewport = () => {}

  function position() {
    if (!anchor.value) return
    const rect = anchor.value.getBoundingClientRect()
    const viewport = window.visualViewport
    const viewportWidth = viewport?.width ?? window.innerWidth
    const viewportHeight = viewport?.height ?? window.innerHeight
    const viewportLeft = viewport?.offsetLeft ?? 0
    const viewportTop = viewport?.offsetTop ?? 0
    const actualWidth = Math.min(width, viewportWidth - 24)
    const height = Math.min(panel.value?.offsetHeight || estimatedHeight, viewportHeight - 32)
    const left = Math.max(viewportLeft + 12, Math.min(rect.right - actualWidth, viewportLeft + viewportWidth - actualWidth - 12))
    const below = rect.bottom + 8
    const viewportBottom = viewportTop + viewportHeight - 12
    const spaceBelow = viewportBottom - below
    // 向下展开至少要有这么高才值得压缩，否则退回到上方。
    const minUsableHeight = 140
    const openBelow = options.preferBelow && spaceBelow >= minUsableHeight
    const top = openBelow || below + height <= viewportBottom
      ? below
      : Math.max(viewportTop + 12, rect.top - height - 8)
    const maxHeight = Math.max(minUsableHeight, viewportBottom - top)
    panelStyle.value = { position: 'fixed', width: `${actualWidth}px`, left: `${left}px`, top: `${top}px`, maxHeight: `${maxHeight}px` }
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
    unbindVisualViewport = bindVisualViewport(window.visualViewport, repositionOpenPanel)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', repositionOpenPanel)
    window.removeEventListener('scroll', repositionOpenPanel, true)
    unbindVisualViewport()
  })
  return { anchor, panel, panelStyle, place }
}
