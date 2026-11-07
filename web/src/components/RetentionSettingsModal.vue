<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { formatNumber } from '../utils/format'
import type { RetentionPolicy } from '../types/api'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps<{ policy: RetentionPolicy }>()
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const enabled = ref(props.policy.cleanupEnabled)
const saving = ref(false)
const cleaning = ref(false)
const lastCleanup = ref<number | null>(null)

async function save(): Promise<void> {
  if (saving.value) return
  saving.value = true
  try {
    await api.updateRetentionPolicy({ cleanupEnabled: enabled.value })
    notify(`自动清理已${enabled.value ? '开启' : '关闭'}`)
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    saving.value = false
  }
}

async function cleanup(): Promise<void> {
  if (cleaning.value) return
  if (!window.confirm('清理超过保留期的请求日志？当天的额度账本不会删除。')) return
  cleaning.value = true
  try {
    const result = await api.cleanupRequestLogs()
    lastCleanup.value = result.deleted
    notify(`已清理 ${formatNumber(result.deleted)} 条请求日志`)
    emit('saved')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    cleaning.value = false
  }
}
</script>

<template>
  <Modal title="请求日志保留" @close="$emit('close')">
    <form id="retention-settings-form" class="form-grid" @submit.prevent="save">
      <label class="switch-row">
        <input v-model="enabled" type="checkbox" :disabled="saving" />
        <span class="switch"></span>
        <span>
          <strong>自动清理过期请求日志</strong>
          <small>开启后网关会定期删除超过保留期限的请求日志明细，每日额度账本不受影响。</small>
        </span>
      </label>

      <div class="callout info">
        保留期限与清理批次由部署配置决定，保存后立即生效。关闭自动清理不会删除已有日志。
      </div>

      <div class="retention-manual">
        <div>
          <strong>立即清理</strong>
          <small>手动执行一次过期日志清理，可用于释放存储空间。</small>
        </div>
        <button type="button" class="button subtle" :disabled="cleaning || saving" @click="cleanup">
          <Icon name="trash" :size="14" />
          {{ cleaning ? '清理中…' : '立即清理' }}
        </button>
      </div>

      <p v-if="lastCleanup !== null" class="muted small">
        最近一次清理删除了 {{ formatNumber(lastCleanup) }} 条请求日志。
      </p>
    </form>

    <template #footer>
      <button type="button" class="button ghost" :disabled="saving" @click="$emit('close')">取消</button>
      <button type="submit" form="retention-settings-form" class="button primary" :disabled="saving || cleaning">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.retention-manual {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.retention-manual div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.retention-manual strong {
  font-size: 13px;
  font-weight: 650;
}

.retention-manual small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.retention-manual .button {
  flex: 0 0 auto;
}

@media (max-width: 520px) {
  .retention-manual {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
