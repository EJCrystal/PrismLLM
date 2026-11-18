<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { useTheme } from '../composables/useTheme'
import { getPaletteDisplayName, type ThemePaletteId } from '../utils/theme'
import Icon from './Icon.vue'
import ThemePaletteIcon from './ThemePaletteIcon.vue'

const {
  theme,
  palette,
  palettes,
  currentPreset,
  currentPaletteName,
  isDark,
  setPalette,
  toggleTheme,
} = useTheme()

const menuId = useId()
const open = ref(false)
const activeIndex = ref(0)
const rootRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)

function getMenuItems(): HTMLButtonElement[] {
  return [...(menuRef.value?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') ?? [])]
}

function focusMenuItem(index: number): void {
  const items = getMenuItems()
  if (!items.length) return
  const normalized = ((index % items.length) + items.length) % items.length
  activeIndex.value = normalized
  items[normalized]?.focus()
}

async function openMenu(preferLast = false): Promise<void> {
  open.value = true
  await nextTick()
  const items = getMenuItems()
  if (!items.length) return
  const selectedIndex = palettes.findIndex((item) => item.id === palette.value)
  const targetIndex = selectedIndex >= 0 ? selectedIndex : preferLast ? items.length - 1 : 0
  activeIndex.value = targetIndex
  items[targetIndex]?.focus()
}

async function closeMenu(restoreFocus = false): Promise<void> {
  if (!open.value) return
  open.value = false
  if (restoreFocus) {
    await nextTick()
    triggerRef.value?.focus()
  }
}

function toggleMenu(): void {
  if (open.value) {
    void closeMenu(true)
  } else {
    void openMenu(false)
  }
}

function selectPalette(id: ThemePaletteId, restoreFocus = true): void {
  setPalette(id)
  void closeMenu(restoreFocus)
}

function handleTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (open.value && event.key !== 'ArrowDown') {
      void closeMenu(true)
    } else {
      void openMenu(false)
    }
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    void openMenu(true)
    return
  }
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    event.stopPropagation()
    void closeMenu(true)
  }
}

function handleMenuKeydown(event: KeyboardEvent): void {
  const items = getMenuItems()
  if (!items.length) return
  const focusedIndex = items.findIndex((item) => item === document.activeElement)
  const currentIndex = focusedIndex >= 0 ? focusedIndex : activeIndex.value

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      focusMenuItem(currentIndex + 1)
      break
    case 'ArrowUp':
      event.preventDefault()
      focusMenuItem(currentIndex - 1)
      break
    case 'Home':
      event.preventDefault()
      focusMenuItem(0)
      break
    case 'End':
      event.preventDefault()
      focusMenuItem(items.length - 1)
      break
    case 'Enter':
    case ' ': {
      event.preventDefault()
      const selected = palettes[currentIndex]
      if (selected) {
        selectPalette(selected.id, true)
      }
      break
    }
    case 'Escape':
      event.preventDefault()
      event.stopPropagation()
      void closeMenu(true)
      break
    case 'Tab':
      void closeMenu(false)
      break
  }
}

function handleDocumentPointerDown(event: PointerEvent): void {
  if (!open.value) return
  if (rootRef.value && event.target instanceof Node && !rootRef.value.contains(event.target)) {
    void closeMenu(false)
  }
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    void closeMenu(true)
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div ref="rootRef" class="theme-controls">
    <div class="theme-capsule-wrap">
      <button
        ref="triggerRef"
        type="button"
        class="theme-capsule-btn"
        :aria-expanded="open"
        :aria-controls="open ? menuId : undefined"
        aria-haspopup="menu"
        :title="`切换配色主题（当前：${currentPaletteName}）`"
        :aria-label="`切换配色主题，当前 ${currentPaletteName}`"
        @click="toggleMenu"
        @keydown="handleTriggerKeydown"
      >
        <ThemePaletteIcon :palette="palette" :size="15" />
        <span class="theme-capsule-label">{{ currentPaletteName }}</span>
        <Icon name="chevron" :size="12" class="theme-capsule-caret" />
      </button>

      <div
        v-if="open"
        :id="menuId"
        ref="menuRef"
        class="theme-menu"
        role="menu"
        aria-label="主题配色方案"
        @keydown="handleMenuKeydown"
      >
        <div class="theme-menu-head">
          <span>主题配色</span>
          <small>{{ isDark ? '深色模式' : '浅色模式' }}</small>
        </div>
        <button
          v-for="(item, index) in palettes"
          :key="item.id"
          type="button"
          role="menuitemradio"
          class="theme-menu-item"
          :class="{ 'is-active': palette === item.id }"
          :aria-checked="palette === item.id"
          :tabindex="activeIndex === index ? 0 : -1"
          @click="selectPalette(item.id)"
        >
          <ThemePaletteIcon :palette="item.id" :size="16" />
          <span class="theme-menu-copy">
            <strong>{{ getPaletteDisplayName(item.id, theme) }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <span
            class="theme-menu-swatch"
            :style="{ backgroundColor: isDark ? item.darkAccent : item.lightAccent }"
            aria-hidden="true"
          ></span>
          <Icon v-if="palette === item.id" name="check" :size="14" class="theme-menu-check" />
        </button>
      </div>
    </div>

    <button
      type="button"
      class="theme-toggle-btn"
      :title="currentPreset.tip"
      :aria-label="currentPreset.tip"
      @click="toggleTheme"
    >
      <Icon :name="currentPreset.toggleIcon" :size="17" />
    </button>
  </div>
</template>
