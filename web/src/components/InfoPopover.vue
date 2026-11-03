<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import Icon from './Icon.vue'

defineProps<{ label: string; tone?: string }>()
const id = useId()
const hovered = ref(false)
const pinned = ref(false)
const focused = ref(false)
const shown = computed(() => hovered.value || pinned.value || focused.value)
const { anchor, panel, panelStyle, place } = useAnchoredPanel(310, 190)
let timer: ReturnType<typeof setTimeout> | undefined
function enter() { clearTimeout(timer); hovered.value = true }
function leave() { timer = setTimeout(() => { hovered.value = false }, 140) }
function close() { hovered.value = false; pinned.value = false; focused.value = false }
function toggle() { if (pinned.value) close(); else pinned.value = true }
function outside(event: PointerEvent) {
  if (!shown.value) return
  const target = event.target as Node
  if (!anchor.value?.contains(target) && !panel.value?.contains(target)) close()
}
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && shown.value) { close(); anchor.value?.focus(); focused.value = false } }
watch(shown, (value) => { if (value) place() })
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape) })
onBeforeUnmount(() => { clearTimeout(timer); document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) })
</script>

<template>
  <button ref="anchor" type="button" class="info-popover-trigger" :class="tone" :aria-label="label" :aria-expanded="shown" :aria-controls="id" @mouseenter="enter" @mouseleave="leave" @focus="focused = true" @blur="focused = false" @click="toggle">
    <slot name="trigger" />
  </button>
  <Teleport to="body">
    <div v-if="shown" :id="id" ref="panel" class="info-popover" :style="panelStyle" role="region" :aria-label="label" @mouseenter="enter" @mouseleave="leave">
      <div class="info-popover-head"><strong>{{ label }}</strong><button type="button" class="icon-button" aria-label="关闭提示" @click="close"><Icon name="close" :size="16" /></button></div>
      <slot />
    </div>
  </Teleport>
</template>
