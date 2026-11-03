<script setup lang="ts">
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import Icon from './Icon.vue'

interface ActionMenuItem { id: string; label: string; icon?: string; danger?: boolean; disabled?: boolean }
const props = withDefaults(defineProps<{ label: string; items: ActionMenuItem[]; icon?: string; compact?: boolean; disabled?: boolean }>(), { icon: 'more', compact: false, disabled: false })
const emit = defineEmits<{ select: [id: string] }>()
const { anchor, panelStyle, place } = useAnchoredPanel(240, props.items.length * 44 + 16)
</script>

<template>
  <Menu as="div" class="action-menu">
    <MenuButton as="template">
      <button ref="anchor" type="button" :class="compact ? 'icon-button more-button' : 'button subtle'" :aria-label="label" :title="label" :disabled="disabled" @click="place" @keydown.down="place" @keydown.up="place" @keydown.enter="place" @keydown.space="place">
        <Icon :name="icon" :size="17" /><span v-if="!compact">{{ label }}</span><Icon v-if="!compact" name="chevron" :size="14" />
      </button>
    </MenuButton>
    <Teleport to="body">
      <MenuItems class="floating-menu" :style="panelStyle">
        <MenuItem v-for="item in items" :key="item.id" v-slot="{ active }" :disabled="item.disabled" as="template">
          <button type="button" class="floating-menu-item" :class="{ active, danger: item.danger }" :disabled="item.disabled" @click="emit('select', item.id)">
            <Icon v-if="item.icon" :name="item.icon" :size="17" /><span>{{ item.label }}</span>
          </button>
        </MenuItem>
      </MenuItems>
    </Teleport>
  </Menu>
</template>
