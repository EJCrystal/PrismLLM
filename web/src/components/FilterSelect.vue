<script setup lang="ts">
import { computed, ref } from 'vue'
import { Popover, PopoverButton, PopoverPanel } from '@headlessui/vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import Icon from './Icon.vue'

const props = withDefaults(defineProps<{
  label: string
  modelValue: string
  options: Array<{ value: string; label: string }>
  defaultValue?: string
  searchable?: boolean
  clearable?: boolean
  disabled?: boolean
}>(), { defaultValue: '', searchable: false, clearable: true, disabled: false })
defineEmits<{ 'update:modelValue': [value: string] }>()
const query = ref('')
const selected = computed(() => props.modelValue !== props.defaultValue)
const selectedLabel = computed(() => props.options.find(option => option.value === props.modelValue)?.label || props.modelValue)
const filteredOptions = computed(() => {
  const search = query.value.trim().toLocaleLowerCase()
  return props.options.filter(option => !search || option.value === props.defaultValue || option.label.toLocaleLowerCase().includes(search))
})
const { anchor, panel, panelStyle, place } = useAnchoredPanel(300, 310)
function openPanel() {
  query.value = ''
  place()
}
</script>

<template>
  <div class="filter-chip-group" :class="{ 'is-active': selected }">
    <Popover v-slot="{ close }" class="filter-select">
      <PopoverButton as="template" :disabled="disabled">
        <button ref="anchor" type="button" class="filter-select-trigger" :aria-label="'按' + label + '筛选：' + (selected ? selectedLabel : '全部')" :disabled="disabled" @click="openPanel" @keydown.enter="openPanel" @keydown.space="openPanel">
          <Icon name="plus-circle" :size="15" />
          <span class="filter-chip-label">{{ label }}</span>
          <span v-if="selected" class="filter-chip-value" :title="selectedLabel">{{ selectedLabel }}</span>
        </button>
      </PopoverButton>
      <Teleport to="body">
        <PopoverPanel as="div" focus>
          <div ref="panel" class="filter-select-panel" :style="panelStyle" :aria-label="label + '筛选选项'">
            <label v-if="searchable" class="search-box filter-select-search">
              <Icon name="search" :size="16" />
              <input v-model="query" type="search" :aria-label="'搜索' + label" :placeholder="'搜索' + label + '…'" autocomplete="off" />
            </label>
            <div class="filter-select-options">
              <button v-for="option in filteredOptions" :key="option.value" type="button" class="filter-select-option" :class="{ 'is-selected': modelValue === option.value }" :aria-pressed="modelValue === option.value" @click="$emit('update:modelValue', option.value); close()">
                <span>{{ option.label }}</span><Icon v-if="modelValue === option.value" name="check" :size="16" />
              </button>
              <p v-if="!filteredOptions.some(option => option.value !== defaultValue) && query.trim()" class="muted small filter-select-empty">没有匹配的{{ label }}</p>
            </div>
          </div>
        </PopoverPanel>
      </Teleport>
    </Popover>
    <button v-if="selected && clearable" type="button" class="filter-chip-clear" :disabled="disabled" :aria-label="'清除' + label + '筛选'" :title="'清除' + label + '筛选'" @click="$emit('update:modelValue', defaultValue)"><Icon name="close" :size="13" /></button>
  </div>
</template>
