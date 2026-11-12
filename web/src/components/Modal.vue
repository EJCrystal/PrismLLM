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
  bodyScrollable: { type: Boolean, default: true },
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
            <div class="modal-title-row">
              <DialogTitle as="h2">{{ title }}</DialogTitle>
              <slot name="title-extra" />
            </div>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="$emit('close')">
            <Icon name="close" :size="18" />
          </button>
        </header>
        <div v-if="$slots.subhead" class="modal-subhead">
          <slot name="subhead" />
        </div>
        <div class="modal-body" :class="{ 'is-contained': !bodyScrollable }">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="modal-foot">
          <slot name="footer" />
        </footer>
      </DialogPanel>
    </Dialog>
  </Teleport>
</template>

<style scoped>
.modal-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  min-width: 0;
}

.modal-title-row h2 {
  min-width: 0;
}
</style>
