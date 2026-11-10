<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '../api'
import type { IqQuestion, IqQuestionInput } from '../types/api'
import { notify } from '../toast'
import Modal from './Modal.vue'

const props = defineProps<{ questions: IqQuestion[] }>()
const emit = defineEmits<{ close: []; change: [questions: IqQuestion[]] }>()
const questions = ref([...props.questions])
const search = ref('')
const editingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)
const busy = ref(false)
const formRef = ref<HTMLFormElement | null>(null)
const error = ref('')
const blank = (): IqQuestionInput => ({ title: '', prompt: '', answerType: 'number', expectedAnswer: '', enabled: true })
const form = reactive<IqQuestionInput>(blank())
const visible = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return questions.value.filter(question => (question.title + '\n' + question.prompt).toLocaleLowerCase().includes(query))
})
function reset() {
  editingId.value = null
  Object.assign(form, blank())
  error.value = ''
}
function edit(question: IqQuestion) {
  editingId.value = question.id
  Object.assign(form, question)
  error.value = ''
  formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function changed() {
  emit('change', [...questions.value])
}
async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const input: IqQuestionInput = { title: form.title, prompt: form.prompt, answerType: form.answerType, expectedAnswer: form.expectedAnswer, enabled: form.enabled }
    const question = editingId.value == null ? await api.createIqQuestion(input) : await api.updateIqQuestion(editingId.value, input)
    questions.value = [question, ...questions.value.filter(item => item.id !== question.id)].sort((a, b) => b.id - a.id)
    changed()
    reset()
    notify('题目已保存')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally { busy.value = false }
}
async function toggle(question: IqQuestion) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const saved = await api.updateIqQuestion(question.id, { title: question.title, prompt: question.prompt, answerType: question.answerType, expectedAnswer: question.expectedAnswer, enabled: !question.enabled })
    questions.value = questions.value.map(item => item.id === saved.id ? saved : item)
    if (editingId.value === saved.id) form.enabled = saved.enabled
    changed()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { busy.value = false }
}
async function remove(question: IqQuestion) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api.deleteIqQuestion(question.id)
    questions.value = questions.value.filter(item => item.id !== question.id)
    if (editingId.value === question.id) reset()
    deletingId.value = null
    changed()
    notify('题目已删除，历史检测记录保留')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { busy.value = false }
}
</script>

<template>
  <Modal title="糖果测试题库" @close="!busy && emit('close')">
    <p class="muted small">每次从启用题目中随机抽一道，允许重复抽取。标准答案仅用于服务端本地判分，不会随题目发送给模型。</p>
    <div v-if="error" class="callout danger" role="alert">{{ error }}</div>
    <form ref="formRef" class="question-form" @submit.prevent="save">
      <strong>{{ editingId == null ? '新增题目' : '编辑题目' }}</strong>
      <label class="field"><span>标题</span><input v-model="form.title" required maxlength="120" :disabled="busy" /></label>
      <label class="field"><span>题干（不要填写标准答案）</span><textarea v-model="form.prompt" required maxlength="20000" rows="6" :disabled="busy"></textarea></label>
      <label class="field"><span>答案类型</span><select v-model="form.answerType" :disabled="busy"><option value="number">数字</option><option value="text">文本</option></select></label>
      <label class="field"><span>标准答案</span><textarea v-model="form.expectedAnswer" required maxlength="2000" rows="2" :disabled="busy"></textarea><small>{{ form.answerType === 'number' ? '按数值比较，支持小数；例如 1 和 1.0 相等。' : '去除首尾空白后精确比较，区分大小写；不作语义评分。' }}</small></label>
      <label class="question-enabled"><input v-model="form.enabled" type="checkbox" :disabled="busy" />启用，参与随机抽题</label>
      <div class="question-actions"><button type="submit" class="button primary" :disabled="busy">{{ busy ? '处理中…' : '保存题目' }}</button><button v-if="editingId != null" type="button" class="button ghost" :disabled="busy" @click="reset">取消编辑</button></div>
    </form>
    <div class="question-list">
      <label class="field"><span>搜索题目（{{ visible.length }}/{{ questions.length }}）</span><input v-model="search" type="search" placeholder="搜索标题或题干…" /></label>
      <p v-if="!visible.length" class="muted small">{{ questions.length ? '没有匹配的题目。' : '题库为空，请在上方添加题目。' }}</p>
      <article v-for="question in visible" :key="question.id" class="question-row">
        <div class="question-title"><strong>{{ question.title }}</strong><span class="badge" :class="question.enabled ? 'success' : 'neutral'">{{ question.enabled ? '已启用' : '已停用' }}</span></div>
        <p class="muted small">{{ question.prompt.slice(0, 100) }}{{ question.prompt.length > 100 ? '…' : '' }}</p>
        <small>标准答案（{{ question.answerType === 'number' ? '数字' : '文本' }}）：<strong class="question-answer">{{ question.expectedAnswer }}</strong></small>
        <div class="question-actions">
          <button type="button" class="button ghost mini" :disabled="busy" @click="edit(question)">编辑</button>
          <button type="button" class="button ghost mini" :disabled="busy" @click="toggle(question)">{{ question.enabled ? '停用' : '启用' }}</button>
          <button v-if="deletingId !== question.id" type="button" class="button ghost mini" :disabled="busy" @click="deletingId = question.id">删除</button>
          <template v-else><span class="small">确认删除？历史记录会保留。</span><button type="button" class="button danger mini" :disabled="busy" @click="remove(question)">确认删除</button><button type="button" class="button ghost mini" :disabled="busy" @click="deletingId = null">取消</button></template>
        </div>
      </article>
    </div>
  </Modal>
</template>

<style scoped>
.question-form, .question-list { display: grid; gap: 12px; padding: 16px 0; }
.question-list { border-top: 1px solid var(--line); }
.question-enabled, .question-actions, .question-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.question-row { display: grid; gap: 10px; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 8px; overflow-wrap: anywhere; }
.question-row p { margin: 0; }
.question-answer { white-space: pre-wrap; }
textarea { width: 100%; resize: vertical; }
</style>
