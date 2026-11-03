<script setup lang="ts">
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import Icon from './Icon.vue'

defineProps({
  title: { type: String, required: true },
  wide: { type: Boolean, default: false },
})
defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <Dialog :open="true" class="modal-backdrop" @close="$emit('close')">
      <DialogPanel as="section" class="modal" :class="{ wide }">
        <header class="modal-head">
          <div>
            <p class="eyebrow">配置</p>
            <DialogTitle as="h2">{{ title }}</DialogTitle>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="$emit('close')">
            <Icon name="close" :size="18" />
          </button>
        </header>
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
