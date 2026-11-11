<script setup lang="ts">
import type { PropType } from 'vue'
import Icon from './Icon.vue'

defineProps({
  loaded: {
    type: Number,
    required: true,
  },
  hasMore: {
    type: Boolean,
    default: false,
  },
  loading: {
    type: Boolean,
    default: false,
  },
  loadingMore: {
    type: Boolean,
    default: false,
  },
  pageSize: {
    type: Number,
    required: true,
  },
  pageSizes: {
    type: Array as PropType<number[]>,
    default: () => [25, 50, 100],
  },
})

const emit = defineEmits<{
  (event: 'load-more'): void
  (event: 'page-size-change', pageSize: number): void
}>()

function handlePageSizeChange(event: Event) {
  if (event.target instanceof HTMLSelectElement) {
    emit('page-size-change', Number(event.target.value))
  }
}
</script>

<template>
  <div v-if="loaded > 0" class="cursor-pager">
    <span class="muted small cursor-pager-status" :class="{ 'has-more': hasMore }">
      <span>已加载 {{ loaded }} 条</span>
      <span class="cursor-pager-tail">{{ hasMore ? '，还有更早记录' : '，已到末尾' }}</span>
    </span>
    <div class="cursor-pager-actions">
      <label class="cursor-page-size">
        <span>每页</span>
        <select
          :value="pageSize"
          :disabled="loading || loadingMore"
          aria-label="每页显示条数"
          @change="handlePageSizeChange"
        >
          <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option>
        </select>
      </label>
      <button
        v-if="hasMore"
        type="button"
        class="button ghost"
        :disabled="loading || loadingMore"
        @click="emit('load-more')"
      >
        <Icon name="down" :size="15" />
        <span class="cursor-pager-more-label-long">{{ loadingMore ? '正在加载…' : '加载更早记录' }}</span>
        <span class="cursor-pager-more-label-short">{{ loadingMore ? '加载中…' : '更早记录' }}</span>
      </button>
    </div>
  </div>
</template>
