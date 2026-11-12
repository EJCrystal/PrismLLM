<script setup lang="ts">
import Icon from './Icon.vue'

defineProps<{ icon: string; title: string; description: string; steps?: string[] }>()
</script>

<template>
  <section class="panel test-empty-state">
    <div class="test-empty-content">
      <div class="test-empty-icon" aria-hidden="true"><Icon :name="icon" :size="26" /></div>
      <h3>{{ title }}</h3>
      <p>{{ description }}</p>
      <ol v-if="steps?.length" class="test-empty-steps">
        <li v-for="(step, index) in steps" :key="index">
          <span aria-hidden="true">{{ index + 1 }}</span>{{ step }}
        </li>
      </ol>
      <slot />
    </div>
  </section>
</template>

<style scoped>
.test-empty-state {
  display: flex;
  flex: 1 0 auto;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  padding: 28px 20px;
  text-align: center;
}

.test-empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  max-width: 440px;
  min-width: 0;
}

.test-empty-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 4px;
  border: 1px solid var(--glass-line);
  border-radius: 16px;
  background: color-mix(in srgb, var(--brand) 9%, var(--panel));
  color: var(--brand);
}

.test-empty-content h3 {
  margin: 0;
  font-size: 16px;
  line-height: 1.5;
}

.test-empty-content p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.test-empty-steps {
  display: grid;
  gap: 10px;
  max-width: 100%;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  text-align: left;
}

.test-empty-steps li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 20px;
  overflow-wrap: anywhere;
}

.test-empty-steps span {
  flex: 0 0 20px;
  border-radius: 50%;
  background: var(--glass-field-strong);
  color: var(--muted);
  text-align: center;
  font-size: 11px;
}

@media (max-width: 1000px) {
  .test-empty-state {
    min-height: 200px;
    padding: 24px 16px;
  }
}
</style>
