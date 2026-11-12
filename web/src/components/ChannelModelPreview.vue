<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  items: { displayName: string; title: string; priceSource: string; priceLabel: string }[]
  listId: string
  expanded: boolean
  limit?: number
}>(), { limit: 2 })
defineEmits<{ toggle: [] }>()
const visible = computed(() => props.expanded ? props.items : props.items.slice(0, props.limit))
const hidden = computed(() => Math.max(0, props.items.length - props.limit))
</script>

<template>
  <div class="channel-model-preview" :class="{ 'is-expanded': expanded }">
    <div :id="listId" class="channel-model-list">
      <span v-for="item in visible" :key="item.displayName" class="channel-model-entry" :title="item.title">
        <span class="badge info channel-model-name">{{ item.displayName }}</span>
        <span v-if="item.priceSource === 'none'" class="channel-model-price-flag">{{ item.priceLabel }}</span>
        <span v-else class="channel-model-price-source" :class="'is-' + item.priceSource">{{ item.priceLabel }}</span>
      </span>
    </div>
    <span v-if="hidden" class="channel-model-expand-slot">
      <button type="button" class="channel-model-expand" :aria-expanded="expanded" :aria-controls="listId" :aria-label="expanded ? '收起授权模型' : '展开其余 ' + hidden + ' 个授权模型'" :title="expanded ? '收起授权模型' : '展开其余 ' + hidden + ' 个授权模型'" @click="$emit('toggle')">
        {{ expanded ? '收起' : '+' + hidden }}
      </button>
    </span>
  </div>
</template>

<style scoped>
.channel-model-preview { display: flex; align-items: flex-start; gap: 6px; min-width: 0; }
.channel-model-list { flex: 1 1 auto; min-width: 0; }
.channel-model-entry { min-height: var(--channel-first-line-height, 24px); }
.channel-model-entry .channel-model-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.is-expanded .channel-model-name { overflow: visible; white-space: normal; overflow-wrap: anywhere; }
.channel-model-expand-slot { display: flex; flex: 0 0 auto; align-items: center; min-height: var(--channel-first-line-height, 24px); }
.channel-model-expand { display: inline-flex; align-items: center; justify-content: center; min-width: 28px; min-height: 24px; padding: 2px 7px; border: 1px solid transparent; border-radius: 999px; background: var(--glass-field); color: var(--muted); font: inherit; font-size: 11px; font-weight: 500; line-height: 18px; cursor: pointer; }
.channel-model-expand:hover { color: var(--brand); background: var(--info-soft); }
.channel-model-expand:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 640px) { .channel-model-expand { min-height: 40px; min-width: 40px; } }
</style>
