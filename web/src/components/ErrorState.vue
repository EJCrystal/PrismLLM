<script setup lang="ts">
import Icon from './Icon.vue'

withDefaults(defineProps<{
  message: string
  title?: string
  retryText?: string
  busy?: boolean
}>(), {
  title: '读取失败',
  retryText: '重试',
  busy: false,
})

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="error-state callout danger" role="alert">
    <span class="error-state-icon" aria-hidden="true"><Icon name="close" :size="16" /></span>
    <div class="error-state-copy">
      <strong>{{ title }}</strong>
      <p>{{ message }}</p>
    </div>
    <button type="button" class="button ghost mini error-state-retry" :disabled="busy" @click="$emit('retry')">
      <Icon name="refresh" :size="14" />
      {{ busy ? '重试中…' : retryText }}
    </button>
  </div>
</template>
