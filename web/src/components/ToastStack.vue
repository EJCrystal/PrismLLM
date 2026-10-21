<script setup>
import { toasts } from '../toast'
</script>

<template>
  <div class="toast-stack" aria-live="polite">
    <div v-for="toast in toasts" :key="toast.id" class="toast" :class="`toast-${toast.type}`">
      <span class="toast-dot"></span>
      <span>{{ toast.message }}</span>
    </div>
  </div>
</template>
