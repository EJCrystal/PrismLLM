<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { copyText } from '../clipboard.js'
import { notify } from '../toast.js'
import Icon from './Icon.vue'

const props = defineProps({
  text: {
    type: String,
    required: true,
  },
  title: {
    type: String,
    default: '复制',
  },
  successTip: {
    type: String,
    default: '',
  },
  variant: {
    type: String,
    default: 'icon', // 'icon' | 'list' | 'subtle'
  },
  size: {
    type: [Number, String],
    default: 13,
  },
})

const copied = ref(false)
let timer = null

async function handleCopy() {
  if (!props.text) return
  try {
    await copyText(props.text)
    copied.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      copied.value = false
    }, 1500)
    const tip = props.successTip || (props.title.endsWith('复制') ? `${props.title}成功` : `${props.title}已复制`)
    notify(tip)
  } catch {
    window.prompt(props.title || '复制文本', props.text)
  }
}

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <button
    type="button"
    :class="[
      variant === 'subtle' ? 'button subtle' : 'icon-button',
      { 'list-copy': variant === 'list' }
    ]"
    :title="title"
    :aria-label="title"
    @click.stop="handleCopy"
  >
    <Icon :name="copied ? 'check' : 'copy'" :size="size" :class="{ 'text-success': copied }" />
    <template v-if="variant === 'subtle'">
      <slot>{{ copied ? '已复制' : '复制' }}</slot>
    </template>
  </button>
</template>
