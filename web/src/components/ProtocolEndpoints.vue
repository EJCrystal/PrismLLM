<script setup lang="ts">
import { computed } from 'vue'
import { protocolConversion } from '../utils/requestLogDisplay'
import ProtocolIcon from './ProtocolIcon.vue'

const props = withDefaults(
  defineProps<{
    inbound: string
    outbound?: string | null
    size?: number
  }>(),
  {
    outbound: null,
    size: 16,
  },
)

const info = computed(() => protocolConversion(props.inbound, props.outbound))
</script>

<template>
  <!-- 跨协议转换：清晰呈现由入站至出站的流转 -->
  <span
    v-if="info.converted"
    class="protocol-endpoints is-converted"
    role="img"
    :aria-label="info.title"
    :title="info.title"
  >
    <span class="protocol-endpoint">
      <ProtocolIcon v-if="info.inbound.known" :protocol="info.inbound.value" :size="size" />
      <span v-else class="protocol-endpoint-unknown" aria-hidden="true">?</span>
      <span class="protocol-endpoint-name">{{ info.inbound.shortName }}</span>
    </span>
    <span class="protocol-endpoint-link is-converted" aria-hidden="true">
      <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 8h14l-3.5-3.5M20 16H6l3.5 3.5" />
      </svg>
    </span>
    <span v-if="info.outbound" class="protocol-endpoint">
      <ProtocolIcon v-if="info.outbound.known" :protocol="info.outbound.value" :size="size" />
      <span v-else class="protocol-endpoint-unknown" aria-hidden="true">?</span>
      <span class="protocol-endpoint-name">{{ info.outbound.shortName }}</span>
    </span>
  </span>

  <!-- 未发生转换（透传 / 无请求）：极简展示单协议徽标，删减重复噪音 -->
  <span
    v-else
    class="protocol-endpoints"
    role="img"
    :aria-label="info.title"
    :title="info.title"
  >
    <span class="protocol-endpoint">
      <ProtocolIcon v-if="info.inbound.known" :protocol="info.inbound.value" :size="size" />
      <span v-else class="protocol-endpoint-unknown" aria-hidden="true">?</span>
      <span class="protocol-endpoint-name">{{ info.inbound.shortName }}</span>
    </span>
  </span>
</template>
