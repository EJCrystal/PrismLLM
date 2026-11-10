<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from './Icon.vue'

const props = defineProps<{
  modelValue: string
  label: string
  options: Array<{ value: string; label: string; searchText?: string }>
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const query = ref('')
const matched = computed(() => {
  const search = query.value.trim().toLocaleLowerCase()
  return props.options.filter(option => (option.searchText ?? option.label).toLocaleLowerCase().includes(search))
})
const retained = computed(() => props.options.find(option => option.value === props.modelValue && !matched.value.includes(option)))
</script>

<template>
  <div class="search-select">
    <div class="search-select-filter">
      <Icon name="search" :size="15" />
      <input v-model="query" type="search" :aria-label="'搜索' + label" :placeholder="'搜索' + label + '…'" :disabled="disabled" autocomplete="off" />
    </div>
    <select :value="modelValue" :aria-label="label" :disabled="disabled || !options.length" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
      <option v-if="retained" :value="retained.value">{{ retained.label }}（当前选择）</option>
      <option v-for="option in matched" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
    <small v-if="query.trim()" class="search-select-meta">{{ matched.length ? `${matched.length} 个匹配` : `没有匹配的${label}` }}{{ retained ? '，当前选择保留' : '' }}</small>
  </div>
</template>

<style scoped>
.search-select { display: grid; gap: 6px; min-width: 0; }
.search-select-filter { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 0 10px; border: 1px solid var(--line); border-radius: 8px; color: var(--muted); background: var(--panel); }
.search-select-filter:focus-within { border-color: var(--primary); }
.search-select-filter input { min-width: 0; width: 100%; padding: 8px 0; border: 0; outline: 0; background: transparent; color: var(--ink); }
.search-select select { width: 100%; min-width: 0; }
.search-select-meta { color: var(--muted); font-size: 11px; line-height: 1.4; }
</style>
