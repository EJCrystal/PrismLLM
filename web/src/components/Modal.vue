<script setup lang="ts">
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import Icon from './Icon.vue'

defineProps({
  title: { type: String, required: true },
  eyebrow: { type: String, default: '配置' },
  wide: { type: Boolean, default: false },
  roomy: { type: Boolean, default: false },
  mobileFullscreen: { type: Boolean, default: false },
  full: { type: Boolean, default: false },
})
defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <Dialog :open="true" class="modal-backdrop" :class="{ 'is-fullscreen': mobileFullscreen || full }" @close="$emit('close')">
      <DialogPanel as="section" class="modal" :class="{ wide, roomy, full, 'mobile-fullscreen': mobileFullscreen }">
        <header class="modal-head">
          <div>
            <p class="eyebrow">{{ eyebrow }}</p>
            <DialogTitle as="h2">{{ title }}</DialogTitle>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="$emit('close')">
            <Icon name="close" :size="18" />
          </button>
        </header>
        <div v-if="$slots.subhead" class="modal-subhead">
          <slot name="subhead" />
        </div>
        <div class="modal-body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="modal-foot">
          <slot name="footer" />
        </footer>
      </DialogPanel>
    </Dialog>
  </Teleport>
</template>
