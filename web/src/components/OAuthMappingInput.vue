<script setup lang="ts">
import SearchSelect from './SearchSelect.vue'

// Local editable wrapper: choosing even the current automatic ID is an explicit manual edit.
withDefaults(defineProps<{
  modelValue: string
  upstreamModel: string
  options: Array<{ value: string; label: string; hint?: string; searchText?: string }>
  disabled?: boolean
  inline?: boolean
  pickerText?: string
}>(), {
  disabled: false,
  inline: false,
  pickerText: '',
})
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="oauth-mapping-input" :class="{ 'is-inline': inline }">
    <input
      :value="modelValue"
      :aria-label="upstreamModel + ' 对外模型 ID'"
      :disabled="disabled"
      autocomplete="off"
      placeholder="留空沿用上游名"
      title="留空沿用上游 ID；输入值作为对外 ID（最多 200 个字符）"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <SearchSelect
      :model-value="modelValue"
      :label="upstreamModel + ' 对外模型 ID'"
      :options="options"
      :disabled="disabled"
      :panel-width="360"
      :trigger-text="pickerText"
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
.oauth-mapping-input.is-inline {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
}
.oauth-mapping-input > input {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: 32px;
  min-height: 32px;
  padding: 5px 10px;
  border: 1px solid var(--glass-line);
  border-radius: 8px;
  outline: 0;
  background-color: var(--glass-field-strong);
  color: var(--ink);
  font-family: ui-monospace, 'SFMono-Regular', 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.4;
  transition: border-color 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}
.oauth-mapping-input > input:hover:not(:disabled) {
  border-color: var(--line-strong);
}
.oauth-mapping-input > input:focus {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 14%, transparent);
}
.oauth-mapping-input > input::placeholder {
  color: var(--muted);
  opacity: 0.75;
  font-family: inherit;
}
.oauth-mapping-input > input:disabled {
  opacity: 0.72;
  cursor: not-allowed;
}
/* Only this editable wrapper constrains the shared SearchSelect's intrinsic width. */
.oauth-mapping-input :deep(.search-select),
.oauth-mapping-input :deep(.search-select-trigger) {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
.oauth-mapping-input.is-inline :deep(.search-select),
.oauth-mapping-input.is-inline :deep(.search-select-trigger) {
  width: auto;
}
@media (prefers-reduced-transparency: reduce) {
  .oauth-mapping-input > input,
  .oauth-mapping-input > input:hover:not(:disabled),
  .oauth-mapping-input > input:focus {
    background-color: var(--panel);
    border-color: var(--line);
  }
}
</style>
