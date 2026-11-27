<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { Channel, SystemPromptRule, SystemPromptSettings } from '../types/api'
import {
  changeSystemPromptScope,
  createSystemPromptRule,
  requiresPromptChannels,
  requiresPromptProtocols,
  systemPromptProtocols,
  systemPromptRuleError,
  systemPromptScopes,
} from '../utils/systemPromptSettings'
import { notify } from '../toast'
import Modal from './Modal.vue'

const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>()
const settings = ref<SystemPromptSettings | null>(null)
const channels = ref<Channel[]>([])
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
const expandedId = ref('')

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [configuration, availableChannels] = await Promise.all([api.getSystemPromptSettings(), api.listChannels()])
    settings.value = configuration
    channels.value = availableChannels
    expandedId.value = configuration.rules[0]?.id ?? ''
  } catch (cause) {
    loadError.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function close() {
  if (!saving.value) emit('close')
}

function addRule() {
  if (!settings.value) return
  const rule = createSystemPromptRule()
  settings.value.rules.push(rule)
  expandedId.value = rule.id
  saveError.value = ''
}

function removeRule(rule: SystemPromptRule) {
  if (!settings.value || !window.confirm('删除规则“' + (rule.name || '未命名规则') + '”？保存后生效。')) return
  settings.value.rules = settings.value.rules.filter(candidate => candidate.id !== rule.id)
}

function moveRule(index: number, offset: number) {
  const rules = settings.value?.rules
  if (!rules || index + offset < 0 || index + offset >= rules.length) return
  const [rule] = rules.splice(index, 1)
  if (rule) rules.splice(index + offset, 0, rule)
}

function onToggle(id: string, event: Event) {
  if ((event.target as HTMLDetailsElement).open) expandedId.value = id
}

function missingChannels(rule: SystemPromptRule): string[] {
  const available = new Set(channels.value.map(channel => channel.id))
  return rule.channelIds.filter(id => !available.has(id))
}

async function save() {
  if (!settings.value || saving.value) return
  saveError.value = ''
  const available = new Set(channels.value.map(channel => channel.id))
  for (const rule of settings.value.rules) {
    const error = systemPromptRuleError(rule, available)
    if (error) {
      expandedId.value = rule.id
      saveError.value = (rule.name || '未命名规则') + '：' + error
      return
    }
  }
  saving.value = true
  try {
    await api.updateSystemPromptSettings({ enabled: settings.value.enabled, rules: settings.value.rules })
    notify('系统提示词配置已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    saveError.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <Modal title="系统提示词" eyebrow="全局业务设置" wide mobile-fullscreen @close="close">
    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="loadError" role="alert">
      <p class="text-danger">加载失败：{{ loadError }}</p>
      <button type="button" class="button subtle" @click="load">重新加载</button>
    </div>
    <form v-else-if="settings" id="system-prompt-settings-form" class="prompt-settings" @submit.prevent="save">
      <label class="checkbox-row">
        <input v-model="settings.enabled" type="checkbox" :disabled="saving">
        <span>启用系统提示词规则</span>
      </label>
      <p class="muted small">应用于正常对话和连通性测试。协议按实际出口匹配；IQ、指纹检测和协议探测不受影响。</p>
      <div class="callout warning">
        整段替换会移除客户端原有的系统级指令，可能影响 Agent 的工具使用指导、编码约定和行为。用户对话及工具定义不变。
      </div>
      <p class="muted small">优先顺序：渠道＋协议、渠道、协议、全局。同层级数值越大越优先，同分按列表顺序，首条命中生效。</p>
      <p v-if="!settings.enabled" class="muted small">当前已关闭，可先编辑规则；保存后仍不会覆盖请求。</p>
      <div class="prompt-rule-heading">
        <strong>规则（{{ settings.rules.length }}）</strong>
        <button type="button" class="button ghost mini" :disabled="saving" @click="addRule">添加规则</button>
      </div>
      <p v-if="!settings.rules.length" class="muted">尚无规则。添加规则后选择它要应用的协议或渠道。</p>
      <details
        v-for="(rule, index) in settings.rules" :key="rule.id" class="prompt-rule" :data-rule-id="rule.id"
        :open="expandedId === rule.id" @toggle="onToggle(rule.id, $event)"
      >
        <summary>
          <strong>{{ rule.name || '未命名规则' }}</strong>
          <span class="muted small">{{ systemPromptScopes.find(scope => scope.value === rule.scope)?.label }} · {{ rule.enabled ? '已启用' : '已停用' }}{{ rule.action === 'preserve' ? ' · 不覆盖' : '' }}</span>
        </summary>
        <fieldset class="prompt-rule-body" :disabled="saving">
          <label class="field">
            <span>规则名称</span>
            <input v-model="rule.name" maxlength="100" placeholder="例如：Antigravity 编码助手">
          </label>
          <div class="prompt-fields">
            <label class="field">
              <span>作用域</span>
              <select v-model="rule.scope" @change="changeSystemPromptScope(rule)">
                <option v-for="scope in systemPromptScopes" :key="scope.value" :value="scope.value">{{ scope.label }}</option>
              </select>
            </label>
            <label class="field">
              <span>应用方式</span>
              <select v-model="rule.action">
                <option value="replace">替换系统提示词</option>
                <option value="preserve">不覆盖（豁免其他规则）</option>
              </select>
            </label>
          </div>
          <fieldset v-if="requiresPromptProtocols(rule.scope)" class="prompt-targets">
            <legend>出口协议（可多选）</legend>
            <label v-for="protocol in systemPromptProtocols" :key="protocol.value" class="checkbox-row">
              <input v-model="rule.protocols" type="checkbox" :value="protocol.value">
              <span>{{ protocol.label }}</span>
            </label>
          </fieldset>
          <fieldset v-if="requiresPromptChannels(rule.scope)" class="prompt-targets">
            <legend>渠道（可多选）</legend>
            <div class="prompt-channel-list">
              <label v-for="channel in channels" :key="channel.id" class="checkbox-row">
                <input v-model="rule.channelIds" type="checkbox" :value="channel.id">
                <span>{{ channel.name }}</span>
              </label>
              <label v-for="id in missingChannels(rule)" :key="id" class="checkbox-row text-danger">
                <input v-model="rule.channelIds" type="checkbox" :value="id">
                <span>已删除渠道：{{ id }}（请取消选择）</span>
              </label>
              <p v-if="!channels.length && !rule.channelIds.length" class="muted small">暂无渠道，请先创建渠道。</p>
            </div>
          </fieldset>
          <p v-if="rule.scope === 'channel_protocols'" class="muted small">只对选定渠道中使用选定出口协议的请求生效。</p>
          <label v-if="rule.action === 'replace'" class="field">
            <span>系统提示词</span>
            <textarea v-model="rule.prompt" rows="8" placeholder="输入要发送给上游的完整系统提示词，不会与客户端原文拼接。" />
          </label>
          <p v-else class="muted small">命中后不应用系统提示词覆盖，保留原有请求处理行为。</p>
          <div class="prompt-fields">
            <label class="field">
              <span>优先级（同层级数值越大越优先）</span>
              <input v-model.number="rule.priority" type="number" step="1" min="-2147483648" max="2147483647">
            </label>
            <label class="checkbox-row">
              <input v-model="rule.enabled" type="checkbox">
              <span>启用此规则</span>
            </label>
          </div>
          <div class="prompt-rule-actions">
            <button type="button" class="button ghost mini" :disabled="index === 0" @click="moveRule(index, -1)">上移</button>
            <button type="button" class="button ghost mini" :disabled="index === settings.rules.length - 1" @click="moveRule(index, 1)">下移</button>
            <button type="button" class="button ghost mini text-danger" @click="removeRule(rule)">删除规则</button>
          </div>
        </fieldset>
      </details>
      <p v-if="saveError" class="text-danger" role="alert">{{ saveError }}</p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" :disabled="saving" @click="close">取消</button>
      <button type="submit" form="system-prompt-settings-form" class="button primary" :disabled="loading || saving || !!loadError">
        {{ saving ? '保存中…' : '保存配置' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.prompt-settings, .prompt-rule-body { display: grid; gap: 14px; min-width: 0; }
.prompt-settings > p, .prompt-rule-body > p { margin: 0; }
.prompt-rule-heading, .prompt-rule-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.prompt-rule-heading { justify-content: space-between; }
.prompt-rule { border: 1px solid var(--line); border-radius: 10px; min-width: 0; }
.prompt-rule summary { cursor: pointer; padding: 14px; overflow-wrap: anywhere; }
.prompt-rule summary strong { margin-right: 10px; }
.prompt-rule-body { border: 0; margin: 0; padding: 0 14px 14px; }
.prompt-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; align-items: end; }
.prompt-fields .field, .prompt-fields .checkbox-row { min-width: 0; }
.prompt-targets { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; display: grid; gap: 8px; min-width: 0; }
.prompt-targets legend { padding: 0 4px; font-size: 13px; }
.prompt-channel-list { display: grid; gap: 8px; max-height: 200px; overflow-y: auto; }
.prompt-channel-list span { overflow-wrap: anywhere; min-width: 0; }
.prompt-rule textarea { resize: vertical; min-height: 150px; width: 100%; }
@media (max-width: 600px) {
  .prompt-fields { grid-template-columns: minmax(0, 1fr); }
  .prompt-rule summary span { display: block; margin-top: 4px; }
}
</style>
