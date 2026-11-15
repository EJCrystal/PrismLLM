<script setup lang="ts">
import Icon from './Icon.vue'
import type { ClientKeyAccessMode } from '../types/api'

/**
 * 访问范围的三态分段控制器，替代原先的下拉框。
 * 三个选项文案固定：不限制 / 白名单（仅允许所选）/ 黑名单（排除所选）。
 *
 * @author 文杰
 * @since 2026-11-15
 */
defineProps<{
  modelValue: ClientKeyAccessMode
  /** radiogroup 的无障碍名称，也是测试定位锚点，例如「渠道与密钥模式」。 */
  label: string
  /** 原生 radio 的分组名，同一弹窗内保持唯一。 */
  name: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: ClientKeyAccessMode] }>()

const options: Array<{ value: ClientKeyAccessMode; label: string; sub: string; icon: string }> = [
  { value: 'unrestricted', label: '不限制', sub: '允许全部', icon: 'unlock' },
  { value: 'allowlist', label: '白名单', sub: '仅允许所选', icon: 'shield-check' },
  { value: 'denylist', label: '黑名单', sub: '排除所选', icon: 'shield-ban' },
]
</script>

<template>
  <div class="access-mode-toggle" role="radiogroup" :aria-label="label">
    <label
      v-for="option in options"
      :key="option.value"
      class="mode-segment"
      :class="[`is-${option.value}`, { 'is-active': modelValue === option.value }]"
    >
      <input
        type="radio"
        :name="name"
        :value="option.value"
        :checked="modelValue === option.value"
        @change="emit('update:modelValue', option.value)"
      />
      <Icon :name="option.icon" :size="13" />
      <span class="mode-label">{{ option.label }}</span>
      <span class="mode-sub">（{{ option.sub }}）</span>
    </label>
  </div>
</template>

<style scoped>
.access-mode-toggle {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: color-mix(in srgb, var(--bg) 46%, transparent);
}
.mode-segment {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.mode-segment:hover { color: var(--ink); }
.mode-segment input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: 0;
  opacity: 0;
  pointer-events: none;
}
.mode-segment:has(input:focus-visible) { outline: 2px solid var(--brand); outline-offset: 1px; }
.mode-sub { font-size: 11px; opacity: 0.85; }
.mode-segment.is-active {
  background: var(--glass-bg-strong);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  color: var(--ink);
  font-weight: 600;
}
.mode-segment.is-active.is-allowlist { background: var(--brand); color: #fff; }
.mode-segment.is-active.is-denylist { background: var(--danger); color: #fff; }
.mode-segment.is-active.is-allowlist .mode-sub,
.mode-segment.is-active.is-denylist .mode-sub { color: rgba(255, 255, 255, 0.82); }
@media (max-width: 640px) {
  .access-mode-toggle { grid-template-columns: 1fr; }
  .mode-segment { justify-content: flex-start; }
}
</style>
