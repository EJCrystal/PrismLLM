<script setup lang="ts">
import { ref, useId } from 'vue'
import Icon from './Icon.vue'
withDefaults(defineProps<{ label?: string; summary?: string; count?: number }>(), { label: '筛选', summary: '', count: 0 })
const open = ref(false)
const id = useId()
</script>

<template>
  <div class="filter-panel" :class="{ 'is-open': open }">
    <button type="button" class="filter-panel-toggle" :aria-expanded="open" :aria-controls="id" @click="open = !open">
      <span class="filter-panel-caption"><Icon name="filter" :size="17" /><strong>{{ label }}</strong><span v-if="count" class="badge info">{{ count }}</span></span>
      <span class="filter-panel-summary">{{ summary || '全部条件' }}</span><Icon :name="open ? 'up' : 'chevron'" :size="16" />
    </button>
    <div :id="id" class="filter-panel-body"><slot /></div>
    <div v-if="$slots.chips" class="filter-panel-chips"><slot name="chips" /></div>
  </div>
</template>
