<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Popover, PopoverButton, PopoverPanel } from '@headlessui/vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import Icon from './Icon.vue'

interface SearchSelectOption {
  value: string
  label: string
  hint?: string
  group?: string
  searchText?: string
}

const props = withDefaults(defineProps<{
  modelValue: string
  label: string
  options: SearchSelectOption[]
  placeholder?: string
  disabled?: boolean
  panelWidth?: number
}>(), {
  placeholder: '请选择',
  disabled: false,
  panelWidth: 320,
})

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const query = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const { anchor, panel, panelStyle, place } = useAnchoredPanel(props.panelWidth, 420)

const selected = computed(() => props.options.find(option => option.value === props.modelValue) ?? null)
const triggerHint = computed(() => {
  if (!selected.value) return ''
  return [selected.value.group, selected.value.hint].filter(Boolean).join(' · ')
})
const groupedOptions = computed(() => {
  const groups = new Map<string, SearchSelectOption[]>()
  for (const option of props.options) {
    const key = option.group ?? ''
    const items = groups.get(key)
    if (items) items.push(option)
    else groups.set(key, [option])
  }
  return [...groups.entries()].map(([label, options]) => ({ label, options }))
})
const filteredGroups = computed(() => {
  const search = query.value.trim().toLocaleLowerCase()
  if (!search) return groupedOptions.value
  return groupedOptions.value
    .map(group => ({
      label: group.label,
      options: group.options.filter((option) => {
        const haystack = `${option.searchText ?? option.label} ${option.label} ${group.label}`.toLocaleLowerCase()
        return haystack.includes(search)
      }),
    }))
    .filter(group => group.options.length > 0)
})
const matchedCount = computed(() => filteredGroups.value.reduce((total, group) => total + group.options.length, 0))

function openPanel() {
  query.value = ''
  place()
  nextTick(() => {
    requestAnimationFrame(() => {
      searchInput.value?.focus()
      const options = panel.value?.querySelector<HTMLElement>('.search-select-options')
      const selectedOption = options?.querySelector<HTMLElement>('[data-selected="true"]')
      if (options && selectedOption) {
        const optionsRect = options.getBoundingClientRect()
        const selectedRect = selectedOption.getBoundingClientRect()
        const offset = selectedRect.top - optionsRect.top
        options.scrollTop += offset - (optionsRect.height - selectedRect.height) / 2
      }
    })
  })
}

function choose(value: string, close: () => void) {
  emit('update:modelValue', value)
  close()
}
</script>

<template>
  <Popover v-slot="{ close }" class="search-select">
    <PopoverButton as="template" :disabled="disabled">
      <button
        ref="anchor"
        type="button"
        class="search-select-trigger"
        :disabled="disabled"
        :aria-label="label + '：' + (selected?.label ?? placeholder)"
        @click="openPanel"
        @keydown.enter="openPanel"
        @keydown.space="openPanel"
        @keydown.down.prevent="openPanel"
      >
        <span class="search-select-copy">
          <strong :class="{ 'is-placeholder': !selected }">{{ selected?.label ?? placeholder }}</strong>
          <small v-if="triggerHint">{{ triggerHint }}</small>
        </span>
        <Icon name="chevron" :size="15" class="search-select-chevron" />
      </button>
    </PopoverButton>
    <Teleport to="body">
      <PopoverPanel as="div" focus>
        <div ref="panel" class="search-select-panel" :style="panelStyle" :aria-label="label + '选项'">
          <label class="search-select-search">
            <Icon name="search" :size="15" />
            <input
              ref="searchInput"
              v-model="query"
              type="search"
              :aria-label="'搜索' + label"
              :placeholder="'搜索' + label + '…'"
              autocomplete="off"
            />
            <span v-if="query.trim()" class="search-select-count">{{ matchedCount }}</span>
          </label>
          <div class="search-select-options" role="listbox" :aria-label="label">
            <section v-for="group in filteredGroups" :key="group.label || '__default'" class="search-select-group">
              <span v-if="group.label" class="search-select-group-label">{{ group.label }}</span>
              <button
                v-for="option in group.options"
                :key="option.value"
                type="button"
                role="option"
                class="search-select-option"
                :class="{ 'is-selected': option.value === modelValue }"
                :aria-selected="option.value === modelValue"
                :data-selected="option.value === modelValue ? 'true' : undefined"
                @click="choose(option.value, close)"
              >
                <span class="search-select-option-copy">
                  <strong>{{ option.label }}</strong>
                  <small v-if="option.hint">{{ option.hint }}</small>
                </span>
                <Icon v-if="option.value === modelValue" name="check" :size="15" class="search-select-check" />
              </button>
            </section>
            <p v-if="!matchedCount" class="search-select-empty">没有匹配的{{ label }}</p>
          </div>
        </div>
      </PopoverPanel>
    </Teleport>
  </Popover>
</template>

<style scoped>
.search-select {
  min-width: 0;
}

.search-select-trigger {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 44px;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--sd-border, var(--glass-line));
  border-radius: var(--sd-radius, 10px);
  background: var(--sd-bg, var(--glass-field));
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.search-select-trigger:hover:not(:disabled) {
  border-color: var(--sd-border-hover, var(--glass-highlight));
  background: var(--sd-bg-hover, var(--glass-field-strong));
}

.search-select-trigger[aria-expanded="true"] {
  border-color: color-mix(in srgb, var(--brand) 55%, var(--glass-line));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 12%, transparent);
}

.search-select-trigger:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.search-select-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  gap: 2px;
}

.search-select-copy strong {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-select-copy strong.is-placeholder {
  color: var(--muted);
  font-weight: 500;
}

.search-select-copy small {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-select-chevron {
  flex: 0 0 auto;
  color: var(--muted);
  transition: transform 0.16s ease;
}

.search-select-trigger[aria-expanded="true"] .search-select-chevron {
  transform: rotate(180deg);
}

.search-select-panel {
  display: flex;
  z-index: 1200;
  flex-direction: column;
  overflow: hidden;
  padding: 10px;
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  background: var(--glass-surface-strong);
  box-shadow: var(--glass-shadow), var(--glass-inset);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  animation: search-select-in 0.14s ease both;
}

@keyframes search-select-in {
  from {
    transform: translateY(-4px) scale(0.985);
    opacity: 0;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

.search-select-search {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--glass-line);
  border-radius: 9px;
  background: var(--glass-field);
  color: var(--muted);
}

.search-select-search:focus-within {
  border-color: var(--brand);
}

.search-select-search input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 8px 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
}

.search-select-count {
  flex: 0 0 auto;
  min-width: 18px;
  padding: 1px 5px;
  border-radius: 999px;
  background: var(--info-soft);
  color: var(--info);
  font-size: 10px;
  font-weight: 700;
  text-align: center;
}

.search-select-options {
  display: grid;
  min-height: 0;
  gap: 2px;
  margin-top: 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}

.search-select-group {
  display: grid;
  gap: 1px;
}

.search-select-group + .search-select-group {
  margin-top: 6px;
}

.search-select-group-label {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 6px 8px 4px;
  background: color-mix(in srgb, var(--glass-bg-strong) 92%, transparent);
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.search-select-option {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  gap: 8px;
  padding: 7px 9px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.search-select-option:hover,
.search-select-option:focus-visible {
  outline: none;
  background: var(--info-soft);
}

.search-select-option.is-selected {
  background: color-mix(in srgb, var(--brand) 11%, transparent);
  color: var(--brand);
}

.search-select-option-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  gap: 1px;
}

.search-select-option-copy strong {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-select-option-copy small {
  color: var(--muted);
  font-size: 10.5px;
}

.search-select-check {
  flex: 0 0 auto;
  color: var(--brand);
}

.search-select-empty {
  margin: 4px 0;
  padding: 18px 10px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}
</style>