<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
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

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

function selectPalette(id: ThemePaletteId): void {
  setPalette(id)
  open.value = false
}

function handleDocumentPointerDown(event: PointerEvent): void {
  if (!open.value) return
  if (rootRef.value && event.target instanceof Node && !rootRef.value.contains(event.target)) {
    open.value = false
  }
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && open.value) {
    open.value = false
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
        type="button"
        class="theme-capsule-btn"
        :aria-expanded="open"
        aria-haspopup="menu"
        :title="`切换配色主题（当前：${currentPaletteName}）`"
        :aria-label="`切换配色主题，当前 ${currentPaletteName}`"
        @click="open = !open"
      >
        <ThemePaletteIcon :palette="palette" :size="15" />
        <span class="theme-capsule-label">{{ currentPaletteName }}</span>
        <Icon name="chevron" :size="12" class="theme-capsule-caret" />
      </button>

      <div v-if="open" class="theme-menu" role="menu" aria-label="主题配色方案">
        <div class="theme-menu-head">
          <span>主题配色</span>
          <small>{{ isDark ? '深色模式' : '浅色模式' }}</small>
        </div>
        <button
          v-for="item in palettes"
          :key="item.id"
          type="button"
          role="menuitemradio"
          class="theme-menu-item"
          :class="{ 'is-active': palette === item.id }"
          :aria-checked="palette === item.id"
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
