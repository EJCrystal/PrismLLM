<script setup lang="ts">
import { computed } from 'vue'
import { channelProtocolSlots } from '../utils/channelDisplay'
import ProtocolIcon from './ProtocolIcon.vue'

const props = withDefaults(
  defineProps<{
    protocols?: readonly string[] | null
    size?: number | string
  }>(),
  {
    protocols: () => [],
    size: 24,
  },
)

const slots = computed(() => channelProtocolSlots(props.protocols))
</script>

<template>
  <div class="channel-protocol-matrix" role="group" aria-label="渠道支持协议及优先级">
    <div
      v-for="slot in slots"
      :key="slot.protocol"
      class="channel-protocol-slot"
      :class="{ 'is-active': slot.enabled, 'is-disabled': !slot.enabled }"
      role="img"
      :title="slot.title"
      :aria-label="slot.title"
    >
      <ProtocolIcon :protocol="slot.protocol" :size="size" />
    </div>
  </div>
</template>
