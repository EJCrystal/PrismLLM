<script setup lang="ts">
import { computed, useId } from 'vue'
import type { PluginChoice, PluginField, PluginValue } from '../types/api'

const props = defineProps<{
  field: PluginField
  modelValue?: PluginValue
  options?: PluginChoice[]
  disabled?: boolean
}>()
const emit = defineEmits<{ (event: 'update:modelValue', value: PluginValue): void }>()
const inputId = useId()
const choices = computed(() => props.options ?? props.field.options)
const inputValue = computed(() => String(props.modelValue ?? ''))
const isChoice = computed(() => ['select', 'model', 'credential', 'protocol', 'scope'].includes(props.field.kind))

function update(event: Event) {
  const input = event.target as HTMLInputElement
  if (props.field.kind === 'boolean') emit('update:modelValue', input.checked)
  else if (props.field.kind === 'number') emit('update:modelValue', input.value === '' ? null : Number(input.value))
  else emit('update:modelValue', input.value)
}
</script>

<template>
  <label class="field plugin-field" :class="{ 'plugin-field-toggle': field.kind === 'boolean' }" :for="inputId">
    <span>{{ field.label }}<span v-if="field.required" class="muted" aria-hidden="true"> *</span></span>
    <input v-if="field.kind === 'boolean'" :id="inputId" type="checkbox" :checked="modelValue === true"
      :disabled="disabled" :aria-describedby="field.help ? inputId + '-help' : undefined" @change="update" />
    <select v-else-if="isChoice" :id="inputId" :value="inputValue" :disabled="disabled || !choices.length"
      :required="field.required" :aria-describedby="field.help ? inputId + '-help' : undefined" @change="update">
      <option v-if="!choices.length" value="">没有可用选项</option>
      <option v-for="choice in choices" :key="choice.value" :value="choice.value">{{ choice.label }}</option>
    </select>
    <textarea v-else-if="field.kind === 'textarea'" :id="inputId" :value="inputValue" rows="4"
      :maxlength="field.maxLength ?? undefined" :disabled="disabled" :required="field.required" spellcheck="false"
      :aria-describedby="field.help ? inputId + '-help' : undefined" @input="update"></textarea>
    <input v-else :id="inputId" :type="field.kind === 'number' ? 'number' : 'text'" :value="inputValue"
      :min="field.min ?? undefined" :max="field.max ?? undefined" :step="field.kind === 'number' ? 'any' : undefined"
      :maxlength="field.maxLength ?? undefined" :disabled="disabled" :required="field.required"
      :aria-describedby="field.help ? inputId + '-help' : undefined" @input="update" />
    <small v-if="field.help" :id="inputId + '-help'">{{ field.help }}</small>
  </label>
</template>

<style scoped>
.plugin-field-toggle {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}
.plugin-field-toggle input[type='checkbox'] {
  width: 18px;
  min-width: 18px;
  height: 18px;
  min-height: 0;
  margin: 0;
  padding: 0;
  accent-color: var(--brand);
}
.plugin-field-toggle small { grid-column: 1 / -1; }
</style>
