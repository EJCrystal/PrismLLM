<script setup lang="ts">
import { nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'

withDefaults(defineProps<{ recordMode?: boolean }>(), { recordMode: false })

const activePane = ref<'configuration' | 'results'>('configuration')
const results = ref<HTMLElement | null>(null)
const workspace = ref<HTMLElement | null>(null)
const availableHeight = ref<string>()

function fitVisibleViewport() {
  const viewport = window.visualViewport
  const element = workspace.value
  availableHeight.value = viewport && element?.isConnected
    ? Math.max(0, viewport.height + viewport.offsetTop - element.getBoundingClientRect().top - 16) + 'px'
    : undefined
}

function observeViewport() {
  if (!workspace.value) return
  window.addEventListener('resize', fitVisibleViewport)
  window.visualViewport?.addEventListener('resize', fitVisibleViewport)
  window.visualViewport?.addEventListener('scroll', fitVisibleViewport)
  void nextTick(fitVisibleViewport)
}

function stopObservingViewport() {
  window.removeEventListener('resize', fitVisibleViewport)
  window.visualViewport?.removeEventListener('resize', fitVisibleViewport)
  window.visualViewport?.removeEventListener('scroll', fitVisibleViewport)
}

onMounted(observeViewport)
onActivated(observeViewport)
onDeactivated(stopObservingViewport)
onBeforeUnmount(stopObservingViewport)

function showResults() {
  activePane.value = 'results'
  scrollResults()
}

function scrollResults() {
  void nextTick(() => results.value?.scrollTo({ top: 0 }))
}

defineExpose({ showResults, scrollResults })
</script>

<template>
  <div v-if="recordMode" class="test-record-workspace">
    <slot />
  </div>
  <div v-else ref="workspace" class="test-workbench" :style="{ maxHeight: availableHeight }">
    <div class="test-workbench-switch" role="group" aria-label="测试工作区">
      <button type="button" :aria-pressed="activePane === 'configuration'" @click="activePane = 'configuration'">配置</button>
      <button type="button" :aria-pressed="activePane === 'results'" @click="showResults">结果</button>
    </div>
    <div class="test-workbench-panes" :class="{ 'show-results': activePane === 'results' }">
      <aside class="panel test-workbench-controls" aria-label="测试配置">
        <div class="test-workbench-fields" tabindex="0" role="region" aria-label="测试配置表单">
          <slot name="configuration" />
        </div>
        <footer class="test-workbench-actions">
          <slot name="actions" />
        </footer>
      </aside>
      <section ref="results" class="test-workbench-results" tabindex="0" aria-label="测试结果">
        <slot />
      </section>
    </div>
  </div>
</template>

<style scoped>
.test-workbench {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
}

.test-workbench-switch {
  display: none;
}

.test-workbench-panes {
  display: grid;
  flex: 1 1 0;
  grid-template-columns: minmax(280px, 330px) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.test-workbench-controls {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.test-workbench-fields,
.test-workbench-results {
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scrollbar-gutter: stable;
  scroll-padding-block: 18px;
}

.test-workbench-fields {
  padding: 18px;
}

.test-workbench-results {
  display: flex;
  flex-direction: column;
  gap: 14px;
  scrollbar-gutter: auto;
}

.test-workbench-actions {
  display: grid;
  flex: 0 0 auto;
  gap: 8px;
  padding: 14px 18px;
  border-top: 1px solid var(--glass-line);
  background: var(--glass-surface-strong);
}

.test-workbench-actions :deep(.button) {
  min-height: 42px;
}

.test-workbench-fields:focus-visible,
.test-workbench-results:focus-visible,
.test-workbench-switch button:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
}

.test-record-workspace {
  min-width: 0;
}

.test-workbench-fields :deep(.test-form) {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.test-workbench-fields :deep(.test-form-section) {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.test-workbench-fields :deep(.test-form-section + .test-form-section) {
  padding-top: 16px;
  border-top: 1px solid var(--glass-line);
}

.test-workbench-fields :deep(.test-form-section-title) {
  margin: 0;
  color: var(--ink);
  font-size: 12px;
  font-weight: 650;
  line-height: 20px;
}

.test-workbench-fields :deep(.test-form-note) {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.test-workbench-fields :deep(.test-form-note svg) {
  flex: 0 0 auto;
  margin-top: 2px;
}

.test-workbench-fields :deep(.field) {
  min-width: 0;
}

.test-workbench-fields :deep(.field > span) {
  color: var(--ink);
  font-size: 12px;
  font-weight: 500;
}

.test-workbench-fields :deep(.field select),
.test-workbench-fields :deep(.field input:not([type='radio']):not([type='checkbox'])) {
  height: 44px;
  min-height: 44px;
}

.test-workbench-fields :deep(.test-form-help) {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.test-workbench-fields :deep(.test-form-meta) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.test-workbench-fields :deep(.test-form-help summary) {
  width: fit-content;
  padding: 6px 0;
  cursor: pointer;
}

.test-workbench-fields :deep(.test-form-help summary:focus-visible) {
  outline: 2px solid var(--brand);
  outline-offset: 3px;
  border-radius: 4px;
}

.test-workbench-fields :deep(.test-form-help p) {
  margin: 4px 0 0;
}

.test-workbench-actions :deep(.test-action-note) {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

@media (max-width: 1000px) {
  .test-workbench-fields :deep(.test-form-help summary) {
    min-height: 44px;
    align-content: center;
  }

  .test-workbench-fields :deep(.test-form-meta .link-button) {
    min-height: 44px;
  }

  .test-workbench-switch {
    display: grid;
    flex: 0 0 auto;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px;
    padding: 4px;
    border: 1px solid var(--glass-line);
    border-radius: 12px;
    background: var(--glass-field);
  }

  .test-workbench-switch button {
    min-height: 40px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--muted);
    font: inherit;
    font-size: 13px;
    cursor: pointer;
  }

  .test-workbench-switch button[aria-pressed='true'] {
    background: var(--glass-surface-strong);
    color: var(--brand);
    font-weight: 650;
  }

  .test-workbench-panes {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .test-workbench-controls {
    display: contents;
  }

  .test-workbench-fields {
    padding: 16px;
    border: 1px solid var(--glass-border);
    border-radius: var(--radius);
    background: var(--glass-surface);
  }

  .test-workbench-actions {
    order: 1;
    padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
    border: 1px solid var(--glass-line);
    border-radius: 12px;
  }

  .test-workbench-actions :deep(.button) {
    min-height: 44px;
  }

  .test-workbench-results,
  .show-results .test-workbench-fields {
    display: none;
  }

  .show-results .test-workbench-results {
    display: flex;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .test-workbench-actions,
  .test-workbench-fields,
  .test-workbench-switch,
  .test-workbench-switch button[aria-pressed='true'] {
    background: var(--panel);
  }
}
</style>
