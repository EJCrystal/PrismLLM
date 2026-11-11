<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import {
  EMPTY_TEST_RECORD_FILTERS,
  filterTestRecords,
  mergeTestRecords,
  testRecordChannelOptions,
  testRecordModelOptions,
  testRecordTypeLabel,
  type TestRecordFilters,
  type TestRecordRow,
  type TestRecordTone,
  type TestRecordType,
} from '../utils/testRecords'
import { formatDuration, formatTime } from '../utils/format'
import ErrorState from './ErrorState.vue'
import FilterPanel from './FilterPanel.vue'
import FilterSelect from './FilterSelect.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'

const IqTestView = defineAsyncComponent(() => import('./IqTestView.vue'))
const FingerprintTestView = defineAsyncComponent(() => import('./FingerprintTestView.vue'))

const props = withDefaults(
  defineProps<{ initialRecord?: { type: TestRecordType; id: number } | null }>(),
  { initialRecord: null },
)
const emit = defineEmits<{ (event: 'record-opened'): void }>()

/** 与后端保留策略一致：读取全部保留记录后在本地筛选。 */
const IQ_LIMIT = 200
const FINGERPRINT_LIMIT = 100

const RESULT_LABELS: Record<TestRecordTone, string> = {
  success: '通过',
  warning: '需关注',
  danger: '未通过',
}

const typeOptions = [
  { value: '', label: '全部类型' },
  { value: 'iq', label: '智商' },
  { value: 'fingerprint', label: '指纹' },
]

const resultOptions = [
  { value: '', label: '全部结论' },
  { value: 'success', label: '通过' },
  { value: 'warning', label: '需关注' },
  { value: 'danger', label: '未通过' },
]

const loading = ref(true)
const error = ref('')
const rows = ref<TestRecordRow[]>([])
const filters = ref<TestRecordFilters>({ ...EMPTY_TEST_RECORD_FILTERS })
const activeRecord = ref<{ type: TestRecordType; id: number } | null>(null)

const channelOptions = computed(() => [
  { value: '', label: '全部渠道' },
  ...testRecordChannelOptions(rows.value),
])
const modelOptions = computed(() => [
  { value: '', label: '全部模型' },
  ...testRecordModelOptions(rows.value, filters.value.channelId),
])
const visibleRows = computed(() => filterTestRecords(rows.value, filters.value))
const hasFilters = computed(() => Object.values(filters.value).some(Boolean))
const activeFilterCount = computed(() => Object.values(filters.value).filter(Boolean).length)
const iqCount = computed(() => visibleRows.value.filter((row) => row.type === 'iq').length)
const fingerprintCount = computed(() => visibleRows.value.filter((row) => row.type === 'fingerprint').length)
const activeRecordTitle = computed(() =>
  activeRecord.value ? `${testRecordTypeLabel(activeRecord.value.type)}检测详情` : '检测详情',
)

const filterSummary = computed(() => {
  const parts: string[] = []
  if (filters.value.type) parts.push(testRecordTypeLabel(filters.value.type))
  if (filters.value.channelId) {
    parts.push(
      channelOptions.value.find((option) => option.value === filters.value.channelId)?.label ||
        filters.value.channelId,
    )
  }
  if (filters.value.model) parts.push(filters.value.model)
  const result = filters.value.result
  if (result) parts.push(RESULT_LABELS[result])
  return parts.length ? parts.join(' · ') : '全部检测记录'
})

const filterChips = computed(() => {
  const chips: Array<{ key: keyof TestRecordFilters; label: string }> = []
  if (filters.value.type) {
    chips.push({ key: 'type', label: `类型：${testRecordTypeLabel(filters.value.type)}` })
  }
  if (filters.value.channelId) {
    const label =
      channelOptions.value.find((option) => option.value === filters.value.channelId)?.label ||
      filters.value.channelId
    chips.push({ key: 'channelId', label: `渠道：${label}` })
  }
  if (filters.value.model) chips.push({ key: 'model', label: `模型：${filters.value.model}` })
  const result = filters.value.result
  if (result) chips.push({ key: 'result', label: `结论：${RESULT_LABELS[result]}` })
  return chips
})

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 两类记录分开处理：一类失败时仍展示另一类，避免整页被单个请求拖垮。
    const [iqResult, fingerprintResult] = await Promise.allSettled([
      api.listIqRuns(IQ_LIMIT),
      api.listFingerprintRuns(FINGERPRINT_LIMIT),
    ])
    const failures: string[] = []
    if (iqResult.status === 'rejected') failures.push(`智商记录：${errorMessage(iqResult.reason)}`)
    if (fingerprintResult.status === 'rejected') {
      failures.push(`指纹记录：${errorMessage(fingerprintResult.reason)}`)
    }
    rows.value = mergeTestRecords(
      iqResult.status === 'fulfilled' ? iqResult.value : [],
      fingerprintResult.status === 'fulfilled' ? fingerprintResult.value : [],
    )
    if (failures.length) {
      const prefix = failures.length === 2 ? '检测记录加载失败' : '部分检测记录加载失败'
      const suffix = failures.length === 2 ? '' : '，其余记录已正常加载'
      error.value = `${prefix}（${failures.join('；')}）${suffix}`
    }
  } finally {
    loading.value = false
  }
}

/** 人工判定会改写持久化记录，重新拉取以保证列表与详情一致。 */
function onRecordUpdated() {
  void load()
}

function resetFilters() {
  filters.value = { ...EMPTY_TEST_RECORD_FILTERS }
}

function clearFilter(key: keyof TestRecordFilters) {
  if (key === 'type') filters.value.type = ''
  else if (key === 'channelId') {
    filters.value.channelId = ''
    filters.value.model = ''
  } else if (key === 'model') filters.value.model = ''
  else filters.value.result = ''
}

function openRecord(row: TestRecordRow) {
  activeRecord.value = { type: row.type, id: row.id }
}

watch(
  () => filters.value.channelId,
  (channelId) => {
    if (!filters.value.model) return
    const available = testRecordModelOptions(rows.value, channelId)
    if (!available.some((option) => option.value === filters.value.model)) filters.value.model = ''
  },
)

watch(
  () => props.initialRecord,
  (target) => {
    if (!target || (target.type !== 'iq' && target.type !== 'fingerprint')) return
    activeRecord.value = { type: target.type, id: target.id }
    emit('record-opened')
  },
  { immediate: true },
)

onMounted(load)
</script>

<template>
  <section class="page-stack test-records-page">
    <div class="test-records-toolbar">
      <div class="test-records-summary">
        <strong>{{ visibleRows.length }}</strong>
        <span class="muted">条记录</span>
        <span class="muted small">智商 {{ iqCount }} · 指纹 {{ fingerprintCount }}</span>
      </div>
      <button type="button" class="button ghost mini" :disabled="loading" @click="load">
        <Icon name="refresh" :size="14" />
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <FilterPanel label="记录筛选" :summary="filterSummary" :count="activeFilterCount">
      <div class="test-records-filters">
        <FilterSelect v-model="filters.type" label="类型" :disabled="loading" :options="typeOptions" />
        <FilterSelect
          v-model="filters.channelId"
          label="渠道"
          searchable
          :disabled="loading"
          :options="channelOptions"
        />
        <FilterSelect
          v-model="filters.model"
          label="模型"
          searchable
          :disabled="loading"
          :options="modelOptions"
        />
        <FilterSelect v-model="filters.result" label="结论" :disabled="loading" :options="resultOptions" />
        <button v-if="hasFilters" type="button" class="button ghost mini" @click="resetFilters">
          清除筛选
        </button>
      </div>
      <template #chips>
        <button
          v-for="chip in filterChips"
          :key="chip.key"
          type="button"
          class="filter-chip"
          :aria-label="'移除筛选：' + chip.label"
          @click="clearFilter(chip.key)"
        >
          <span>{{ chip.label }}</span>
          <Icon name="close" :size="13" />
        </button>
      </template>
    </FilterPanel>

    <ErrorState v-if="error && !rows.length" :message="error" :busy="loading" @retry="load" />

    <div v-else class="panel table-panel">
      <div v-if="error" class="callout danger test-records-partial-error">
        <span>{{ error }}</span>
        <button type="button" class="link-button" :disabled="loading" @click="load">重试</button>
      </div>
      <TablePlaceholder
        v-if="loading || visibleRows.length === 0"
        :loading="loading"
        loading-text="正在读取检测记录…"
        :empty="visibleRows.length === 0"
        icon="fingerprint"
        :title="hasFilters ? '没有符合条件的检测记录' : '还没有检测记录'"
        :desc="
          hasFilters
            ? '调整筛选条件，或清除筛选后查看全部记录。'
            : '在渠道列表点击「测试」运行智商或指纹检测，完成后记录会出现在这里。'
        "
        :action-text="hasFilters ? '清除筛选' : ''"
        action-icon="close"
        @action="resetFilters"
      />
      <div v-else class="table-scroll test-records-desktop">
        <table class="data-table test-records-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th>渠道 / 凭据</th>
              <th>模型</th>
              <th>结论</th>
              <th>耗时</th>
              <th><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in visibleRows"
              :key="`${row.type}-${row.id}`"
              class="test-record-row"
              tabindex="0"
              @click="openRecord(row)"
              @keydown.enter="openRecord(row)"
              @keydown.space.prevent="openRecord(row)"
            >
              <td class="test-record-time">{{ formatTime(row.createdAt, { style: 'medium' }) }}</td>
              <td>
                <span class="test-record-type">
                  <Icon :name="row.type === 'iq' ? 'brain' : 'fingerprint'" :size="15" />
                  {{ testRecordTypeLabel(row.type) }}
                </span>
              </td>
              <td>
                <div class="primary-cell">{{ row.channelName }}</div>
                <div class="muted small">{{ row.channelKeyName || '未记录凭据' }}</div>
              </td>
              <td>
                <div class="mono test-record-model">{{ row.model }}</div>
                <div class="muted small">{{ row.protocol }}</div>
              </td>
              <td>
                <span class="badge" :class="row.tone">{{ row.summary }}</span>
                <div class="muted small">
                  <template v-if="row.statusCode">HTTP {{ row.statusCode }}</template>
                  <template v-else>无状态码</template>
                  <template v-if="!row.success"> · 请求失败</template>
                </div>
              </td>
              <td class="mono">{{ formatDuration(row.latencyMs) }}</td>
              <td class="test-record-action">
                <button
                  type="button"
                  class="button ghost mini"
                  :aria-label="`查看${testRecordTypeLabel(row.type)}检测详情：${row.model}`"
                  @click.stop="openRecord(row)"
                >
                  详情
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="visibleRows.length" class="test-record-mobile-list">
        <article
          v-for="row in visibleRows"
          :key="`mobile-${row.type}-${row.id}`"
          class="test-record-card"
          @click="openRecord(row)"
        >
          <header class="test-record-card-head">
            <span class="test-record-type">
              <Icon :name="row.type === 'iq' ? 'brain' : 'fingerprint'" :size="15" />
              {{ testRecordTypeLabel(row.type) }}
            </span>
            <span class="muted small">{{ formatTime(row.createdAt, { style: 'medium' }) }}</span>
          </header>
          <div class="test-record-card-title">
            <strong class="mono">{{ row.model }}</strong>
            <span class="badge" :class="row.tone">{{ row.summary }}</span>
          </div>
          <div class="test-record-card-meta muted small">
            <span>{{ row.channelName }} · {{ row.channelKeyName || '未记录凭据' }}</span>
            <span>{{ row.protocol }}</span>
            <span>
              <template v-if="row.statusCode">HTTP {{ row.statusCode }}</template>
              <template v-else>无状态码</template>
              · {{ formatDuration(row.latencyMs) }}
            </span>
          </div>
          <button
            type="button"
            class="button ghost mini test-record-card-open"
            :aria-label="`查看${testRecordTypeLabel(row.type)}检测详情：${row.model}`"
            @click.stop="openRecord(row)"
          >
            查看详情
          </button>
        </article>
      </div>
    </div>

    <p class="test-records-retention muted small">
      仅保留最近 200 条智商记录与 100 条指纹记录；连通性检测不落库，关闭测试面板后不再保留。
    </p>

    <Modal
      v-if="activeRecord"
      :title="activeRecordTitle"
      eyebrow="检测记录"
      full
      @close="activeRecord = null"
    >
      <IqTestView
        v-if="activeRecord.type === 'iq'"
        :key="`iq-${activeRecord.id}`"
        mode="record"
        :record-id="activeRecord.id"
        @record-updated="onRecordUpdated"
      />
      <FingerprintTestView
        v-else
        :key="`fingerprint-${activeRecord.id}`"
        mode="record"
        :record-id="activeRecord.id"
      />
    </Modal>
  </section>
</template>

<style scoped>
.test-records-page {
  min-width: 0;
}

.test-records-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.test-records-summary {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.test-records-summary strong {
  font-size: 20px;
}

.test-records-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.test-records-table {
  min-width: 780px;
}

.test-record-row {
  cursor: pointer;
}

.test-record-mobile-list {
  display: none;
}

.test-record-row:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
}

.test-record-time {
  white-space: nowrap;
}

.test-record-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.test-record-model {
  overflow-wrap: anywhere;
}

.test-record-action {
  text-align: right;
  white-space: nowrap;
}

.test-records-retention {
  margin: 0;
}

.test-records-partial-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px;
}

@media (max-width: 720px) {
  .test-records-desktop {
    display: none;
  }

  .test-record-mobile-list {
    display: grid;
    gap: 10px;
    padding: 10px;
  }

  .test-record-card {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--glass-line);
    border-radius: 12px;
    background: var(--glass-field);
    cursor: pointer;
  }

  .test-record-card-head,
  .test-record-card-title,
  .test-record-card-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 10px;
  }

  .test-record-card-head,
  .test-record-card-title {
    align-items: center;
    justify-content: space-between;
  }

  .test-record-card-title strong {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .test-record-card-open {
    justify-self: start;
  }
}
</style>
