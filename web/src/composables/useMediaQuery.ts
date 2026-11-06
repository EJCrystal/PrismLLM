import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** Track a CSS media query in a reactive ref. */
export function useMediaQuery(query: string): Ref<boolean> {
  const matches = ref(
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia(query).matches
      : false,
  )
  let media: MediaQueryList | null = null

  const update = () => {
    matches.value = media?.matches ?? false
  }

  onMounted(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    media = window.matchMedia(query)
    update()
    media.addEventListener('change', update)
  })

  onBeforeUnmount(() => {
    media?.removeEventListener('change', update)
  })

  return matches
}
