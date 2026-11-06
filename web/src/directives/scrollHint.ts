import type { ObjectDirective } from 'vue'

const cleanup = new WeakMap<HTMLElement, () => void>()

/** A hint outside the scrolling surface never covers data or reserves a fixed column. */
export const vScrollHint: ObjectDirective<HTMLElement> = {
  mounted(element) {
    const hint = document.createElement('div')
    hint.className = 'table-scroll-hint'
    hint.setAttribute('aria-hidden', 'true')
    const text = document.createElement('span')
    const track = document.createElement('span')
    track.className = 'table-scroll-hint-track'
    const thumb = document.createElement('span')
    track.append(thumb)
    hint.append(text, track)
    element.before(hint)
    const update = () => {
      const max = element.scrollWidth - element.clientWidth
      hint.hidden = max <= 2
      element.classList.toggle('is-h-scrollable', max > 2)
      element.classList.toggle('is-h-scroll-start', element.scrollLeft <= 1)
      element.classList.toggle('is-h-scroll-end', max > 2 && element.scrollLeft >= max - 1)
      const fraction = Math.min(1, Math.max(0, element.scrollLeft / Math.max(1, max)))
      const width = Math.min(1, element.clientWidth / Math.max(1, element.scrollWidth))
      text.textContent = fraction >= .99 ? '已到最右侧，可向回滑动' : fraction > .01 ? '左右滑动查看其余列' : '向左滑动查看其余列'
      thumb.style.width = `${width * 100}%`
      thumb.style.left = `${fraction * (1 - width) * 100}%`
    }
    const resize = new ResizeObserver(update)
    resize.observe(element)
    if (element.firstElementChild) resize.observe(element.firstElementChild)
    const mutations = new MutationObserver(update)
    mutations.observe(element, { childList: true, subtree: true, characterData: true })
    element.addEventListener('scroll', update, { passive: true })
    update()
    cleanup.set(element, () => { resize.disconnect(); mutations.disconnect(); element.removeEventListener('scroll', update); hint.remove() })
  },
  unmounted(element) { cleanup.get(element)?.(); cleanup.delete(element) },
}
