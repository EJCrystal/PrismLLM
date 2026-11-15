<script setup lang="ts">
import SearchSelect from './SearchSelect.vue'

// Local editable wrapper: choosing even the current automatic ID is an explicit manual edit.
defineProps<{
  modelValue: string
  upstreamModel: string
  options: Array<{ value: string; label: string; hint?: string; searchText?: string }>
  disabled?: boolean
}>()
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="oauth-mapping-input">
    <input
      :value="modelValue"
      :aria-label="upstreamModel + ' 手工对外 ID'"
      :disabled="disabled"
      autocomplete="off"
      placeholder="输入对外 ID（1..200 字符）"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <SearchSelect
      :model-value="modelValue"
      :label="upstreamModel + ' 对外 ID'"
      :options="options"
      :disabled="disabled"
      :panel-width="360"
      placeholder="搜索已有 ID"
      compact
      @update:model-value="$emit('update:modelValue', $event)"
    />
  </div>
</template>

<style scoped>
.oauth-mapping-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  width: 100%;
  min-width: 0;
  max-width: 100%;
  gap: 6px;
}
.oauth-mapping-input > input {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
/* Only this editable wrapper constrains the shared SearchSelect's intrinsic width. */
.oauth-mapping-input :deep(.search-select),
.oauth-mapping-input :deep(.search-select-trigger) {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
</style>
