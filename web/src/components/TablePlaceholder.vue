<script setup lang="ts">
import Icon from './Icon.vue'

defineProps({
  loading: {
    type: Boolean,
    default: false,
  },
  loadingText: {
    type: String,
    default: '正在读取数据…',
  },
  empty: {
    type: Boolean,
    default: false,
  },
  icon: {
    type: String,
    default: '',
  },
  title: {
    type: String,
    default: '暂无数据',
  },
  desc: {
    type: String,
    default: '',
  },
  compact: {
    type: Boolean,
    default: false,
  },
  actionText: {
    type: String,
    default: '',
  },
  actionIcon: {
    type: String,
    default: 'plus',
  },
})

defineEmits(['action'])
</script>

<template>
  <div v-if="loading" class="loading-state">
    <div class="loading-state-head"><span class="spinner"></span>{{ loadingText }}</div>
    <div class="loading-skeleton" aria-hidden="true">
      <span v-for="index in (compact ? 2 : 4)" :key="index" :style="{ width: `${58 + index * 9}%` }"></span>
    </div>
  </div>
  <div v-else-if="empty" class="empty-state" :class="{ compact }">
    <div v-if="icon && !compact" class="empty-icon">
      <Icon :name="icon" :size="26" />
    </div>
    <h3>{{ title }}</h3>
    <p v-if="desc">{{ desc }}</p>
    <button v-if="actionText" type="button" class="button primary" @click="$emit('action')">
      <Icon v-if="actionIcon" :name="actionIcon" :size="17" />
      {{ actionText }}
    </button>
  </div>
</template>
