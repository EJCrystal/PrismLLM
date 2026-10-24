<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'

defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  placeholder: {
    type: String,
    default: '',
  },
  autocomplete: {
    type: String,
    default: 'off',
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  readonly: {
    type: Boolean,
    default: false,
  },
  name: {
    type: String,
    default: '',
  },
  id: {
    type: String,
    default: '',
  },
})

defineEmits(['update:modelValue', 'focus', 'blur'])

const show = ref(false)
</script>

<template>
  <div class="password-input-wrap">
    <input
      :id="id || undefined"
      :name="name || undefined"
      :value="modelValue"
      :type="show ? 'text' : 'password'"
      :placeholder="placeholder"
      :autocomplete="autocomplete"
      :disabled="disabled"
      :readonly="readonly"
      @input="$emit('update:modelValue', $event.target.value)"
      @focus="$emit('focus', $event)"
      @blur="$emit('blur', $event)"
    />
    <button
      type="button"
      class="password-toggle-btn"
      :title="show ? '隐藏内容' : '显示内容'"
      :aria-label="show ? '隐藏内容' : '显示内容'"
      @click="show = !show"
    >
      <Icon :name="show ? 'eye-off' : 'eye'" :size="16" />
    </button>
  </div>
</template>
