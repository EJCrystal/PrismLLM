<script setup lang="ts">
import { newKeyDisableRule } from '../utils/keyAutoDisable'
import type { KeyDisableRuleDraft } from '../utils/keyAutoDisable'

/**
 * 规则编辑器字段列表。全局设置与渠道覆盖共用，只负责编辑，不负责加载和保存。
 * 父组件传响应式规则数组，字段直接双向绑定。
 */
const props = defineProps<{
  rules: KeyDisableRuleDraft[]
  enabled: boolean
  saving: boolean
  /** 自动生成的 id 需要避开的规则 id（渠道编辑器传入全局规则 id）。 */
  reservedIds?: string[]
}>()

function addRule() {
  props.rules.push(newKeyDisableRule(props.rules, props.reservedIds))
}

function removeRule(index: number) {
  props.rules.splice(index, 1)
}
</script>

<template>
  <div v-for="(rule, index) in rules" :key="rule.id" class="key-auto-disable-rule">
    <div class="key-auto-disable-rule-head">
      <label class="field grow">
        <span>规则名称</span>
        <input v-model="rule.name" maxlength="40" :disabled="saving || !enabled" />
      </label>
      <button
        type="button" class="button ghost mini" :disabled="saving || rules.length <= 1"
        @click="removeRule(index)"
      >删除</button>
    </div>

    <div class="key-auto-disable-grid">
      <label class="field">
        <span>状态码</span>
        <input v-model="rule.statusCodesText" placeholder="401,403" :disabled="saving || !enabled" />
      </label>
      <label class="field">
        <span>状态码下限</span>
        <input v-model.number="rule.statusCodeAtLeast" type="number" min="100" max="599" placeholder="例如 500" :disabled="saving || !enabled" />
      </label>
      <label class="field">
        <span>触发次数</span>
        <input v-model.number="rule.threshold" type="number" min="1" max="1000" required :disabled="saving || !enabled" />
      </label>
      <label class="field">
        <span>统计窗口（分钟）</span>
        <input v-model.number="rule.windowMinutes" type="number" min="1" max="1440" required :disabled="saving || !enabled" />
      </label>
    </div>

    <label class="field">
      <span>错误文本匹配（每行一个，RE2 正则）</span>
      <textarea
        v-model="rule.bodyPatternsText" rows="3"
        placeholder="invalid_api_key&#10;insufficient_quota"
        :disabled="saving || !enabled"
      ></textarea>
    </label>

    <div class="key-auto-disable-options">
      <label class="check-row">
        <input v-model="rule.streak" type="checkbox" :disabled="saving || !enabled" />
        <span>连续计数（成功清零）</span>
      </label>
      <label class="check-row">
        <input v-model="rule.includeTransportFailures" type="checkbox" :disabled="saving || !enabled" />
        <span>计入连接失败 / 超时</span>
      </label>
    </div>

    <div class="key-auto-disable-grid">
      <label class="field">
        <span>动作</span>
        <select v-model="rule.action" :disabled="saving || !enabled">
          <option value="TEMPORARY">临时停用</option>
          <option value="PERMANENT">永久停用</option>
        </select>
      </label>
      <label v-if="rule.action === 'TEMPORARY'" class="field">
        <span>停用时长（分钟）</span>
        <input v-model.number="rule.disableMinutes" type="number" min="1" max="10080" required :disabled="saving || !enabled" />
      </label>
    </div>
  </div>

  <button type="button" class="button ghost" :disabled="saving || !enabled || rules.length >= 50" @click="addRule">
    添加规则
  </button>
</template>

<style scoped>
.key-auto-disable-rule {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.key-auto-disable-rule-head {
  display: flex;
  align-items: end;
  gap: 10px;
}

.key-auto-disable-rule-head .grow { flex: 1; }

.key-auto-disable-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.key-auto-disable-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}

.check-row input { width: auto; }

.key-auto-disable-rule textarea {
  width: 100%;
  resize: vertical;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 520px) {
  .key-auto-disable-grid { grid-template-columns: 1fr; }
  .key-auto-disable-rule-head { align-items: stretch; flex-direction: column; }
}
</style>
