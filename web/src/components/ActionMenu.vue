<script setup lang="ts">
import { computed, useId, watch } from 'vue'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import Icon from './Icon.vue'

interface ActionMenuItem { id: string; label: string; icon?: string; danger?: boolean; disabled?: boolean; title?: string; group?: string }
const props = withDefaults(defineProps<{ label: string; items: ActionMenuItem[]; icon?: string; compact?: boolean; disabled?: boolean }>(), { icon: 'more', compact: false, disabled: false })
const emit = defineEmits<{ select: [id: string] }>()
const menuId = useId()
let nextGroupKey = 0
const itemGroups = computed<{ key: number; label: string; items: ActionMenuItem[] }[]>((previousGroups = []) => {
  const groups: { label: string; items: ActionMenuItem[] }[] = []
  for (const item of props.items) {
    const label = item.group ?? ''
    const previous = groups[groups.length - 1]
    if (previous?.label === label) previous.items.push(item)
    else groups.push({ label, items: [item] })
  }
  const previousKeys = new Map(previousGroups.flatMap(group => group.items.map(item => [item.id, group.key] as const)))
  const reusedKeys = new Set<number>()
  return groups.map(group => {
    // Surviving items carry group identity; a split may reuse each previous key only once.
    const inheritedKey = group.items.map(item => previousKeys.get(item.id)).find(key => key !== undefined && !reusedKeys.has(key))
    const key = inheritedKey ?? nextGroupKey++
    reusedKeys.add(key)
    return { ...group, key }
  })
})
const hasGroups = computed(() => itemGroups.value.some(group => group.label))
const estimatedPanelHeight = computed(() => props.items.length * 44 + 16
  + itemGroups.value.filter(group => group.label).length * 28
  + Math.max(0, itemGroups.value.length - 1) * 9)
const { anchor, panel, panelStyle, place } = useAnchoredPanel(240, estimatedPanelHeight.value)
watch(panel, (element) => { if (element) void place() }, { flush: 'post' })
watch(() => props.items, () => { if (panel.value) void place() }, { deep: true, flush: 'post' })
</script>

<template>
  <Menu as="div" class="action-menu">
    <!-- Capture placement before HeadlessUI prevents default during keyboard opening. -->
    <MenuButton as="template" :disabled="disabled">
      <button ref="anchor" type="button" :class="compact ? 'icon-button more-button' : 'button subtle'" :aria-label="label" :title="label" :disabled="disabled" @click="place" @keydown.down.capture="place" @keydown.up.capture="place" @keydown.enter.capture="place" @keydown.space.capture="place">
        <Icon :name="icon" :size="17" /><span v-if="!compact">{{ label }}</span><Icon v-if="!compact" name="chevron" :size="14" />
      </button>
    </MenuButton>
    <Teleport to="body">
      <MenuItems as="template">
        <div ref="panel" class="floating-menu" :style="panelStyle">
          <template v-if="hasGroups">
            <template v-for="(group, index) in itemGroups" :key="group.key">
              <div v-if="index > 0" class="floating-menu-group-divider" role="separator" aria-orientation="horizontal" />
              <div role="group" :aria-labelledby="group.label ? `${menuId}-group-${index}` : undefined">
                <div v-if="group.label" :id="`${menuId}-group-${index}`" class="floating-menu-group-heading" role="heading" aria-level="3">{{ group.label }}</div>
                <MenuItem v-for="item in group.items" :key="item.id" v-slot="{ active }" :disabled="item.disabled" as="template">
                  <button type="button" class="floating-menu-item" :class="{ active, danger: item.danger }" :title="item.title" :disabled="item.disabled" @click="emit('select', item.id)">
                    <Icon v-if="item.icon" :name="item.icon" :size="17" /><span>{{ item.label }}</span>
                  </button>
                </MenuItem>
              </div>
            </template>
          </template>
          <template v-else>
            <MenuItem v-for="item in items" :key="item.id" v-slot="{ active }" :disabled="item.disabled" as="template">
              <button type="button" class="floating-menu-item" :class="{ active, danger: item.danger }" :title="item.title" :disabled="item.disabled" @click="emit('select', item.id)">
                <Icon v-if="item.icon" :name="item.icon" :size="17" /><span>{{ item.label }}</span>
              </button>
            </MenuItem>
          </template>
        </div>
      </MenuItems>
    </Teleport>
  </Menu>
</template>
