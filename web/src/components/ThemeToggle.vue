<script setup lang="ts">
import { computed } from 'vue'
import { useTheme } from '../composables/useTheme'
import Icon from './Icon.vue'

defineProps({
  size: {
    type: [Number, String],
    default: 17,
  },
})

const { isDark, currentPreset, toggleTheme } = useTheme()

const buttonTitle = computed(
  () => currentPreset.value?.tip || (isDark.value ? '切换为深色主题' : '切换为浅色主题'),
)
const iconName = computed(() => currentPreset.value?.toggleIcon || 'moon')
</script>

<template>
  <button
    type="button"
    class="theme-toggle-btn"
    :title="buttonTitle"
    :aria-label="buttonTitle"
    @click="toggleTheme"
  >
    <Icon :name="iconName" :size="size" />
  </button>
</template>
