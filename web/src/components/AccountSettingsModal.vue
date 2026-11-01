<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '../api'
import type { MeResponse } from '../types/api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import SecretInput from './SecretInput.vue'

const props = defineProps<{ user: MeResponse }>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'avatar-updated', user: MeResponse): void
  (event: 'password-changed'): void
}>()
const fileInput = ref<HTMLInputElement | null>(null)
const avatarDraft = ref(props.user.avatarDataUrl || null)
const avatarSaving = ref(false)
const passwordSaving = ref(false)
const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const initial = computed(() => props.user.username.slice(0, 1).toUpperCase())
const avatarChanged = computed(() => (avatarDraft.value || null) !== (props.user.avatarDataUrl || null))

function chooseAvatar() {
  fileInput.value?.click()
}

async function handleAvatarFile(event: Event) {
  if (!(event.target instanceof HTMLInputElement)) return
  const input = event.target
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
    notify('头像仅支持 PNG、JPEG 或 WebP', 'error')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    notify('原始图片不能超过 5MB', 'error')
    return
  }
  try {
    const bitmap = await createImageBitmap(file)
    const canvas = document.createElement('canvas')
    canvas.width = 256
    canvas.height = 256
    const context = canvas.getContext('2d')
    if (!context) throw new Error('浏览器无法处理图片')
    const scale = Math.max(256 / bitmap.width, 256 / bitmap.height)
    const width = bitmap.width * scale
    const height = bitmap.height * scale
    context.drawImage(bitmap, (256 - width) / 2, (256 - height) / 2, width, height)
    bitmap.close?.()
    let dataUrl = canvas.toDataURL('image/webp', 0.88)
    if (dataUrl.length > 500_000) dataUrl = canvas.toDataURL('image/webp', 0.7)
    if (dataUrl.length > 500_000) dataUrl = canvas.toDataURL('image/jpeg', 0.72)
    avatarDraft.value = dataUrl
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : '无法读取图片', 'error')
  }
}

async function saveAvatar() {
  avatarSaving.value = true
  try {
    const updated = await api.updateAccountAvatar({ avatarDataUrl: avatarDraft.value })
    emit('avatar-updated', updated)
    notify(avatarDraft.value ? '头像已更新' : '已恢复默认头像')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    avatarSaving.value = false
  }
}

async function changePassword() {
  if (!passwordForm.currentPassword) {
    notify('请输入当前密码', 'error')
    return
  }
  if (passwordForm.newPassword.length < 8) {
    notify('新密码至少需要 8 个字符', 'error')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    notify('两次输入的新密码不一致', 'error')
    return
  }
  if (passwordForm.newPassword === passwordForm.currentPassword) {
    notify('新密码不能与当前密码相同', 'error')
    return
  }
  passwordSaving.value = true
  try {
    await api.changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword,
    })
    notify('密码已更新，请使用新密码重新登录')
    emit('password-changed')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    passwordSaving.value = false
  }
}
</script>

<template>
  <Modal title="账户设置" @close="$emit('close')">
    <div class="account-profile-head">
      <span class="avatar account-avatar">
        <img v-if="avatarDraft" :src="avatarDraft" alt="管理员头像" />
        <template v-else>{{ initial }}</template>
      </span>
      <div class="account-profile-copy">
        <strong>{{ user.username }}</strong>
        <span>管理员账号</span>
      </div>
    </div>

    <section class="account-section">
      <div class="account-section-head">
        <span class="account-section-icon"><Icon name="camera" :size="18" /></span>
        <div>
          <h3>头像</h3>
          <p>上传后会自动裁剪为方形并压缩，仅保存在当前网关。</p>
        </div>
      </div>
      <div class="account-avatar-actions">
        <input
          ref="fileInput"
          class="account-file-input"
          type="file"
          accept="image/png,image/jpeg,image/webp"
          @change="handleAvatarFile"
        />
        <button type="button" class="button subtle" @click="chooseAvatar">选择图片</button>
        <button
          type="button"
          class="button ghost"
          :disabled="!avatarDraft"
          @click="avatarDraft = null"
        >
          恢复默认
        </button>
        <button
          type="button"
          class="button primary account-save-button"
          :disabled="!avatarChanged || avatarSaving"
          @click="saveAvatar"
        >
          {{ avatarSaving ? '保存中…' : '保存头像' }}
        </button>
      </div>
      <small class="account-field-note">支持 PNG、JPEG、WebP，原始文件最大 5MB。</small>
    </section>

    <section class="account-section">
      <div class="account-section-head">
        <span class="account-section-icon"><Icon name="lock" :size="18" /></span>
        <div>
          <h3>修改密码</h3>
          <p>更新后所有管理会话都会失效，需要使用新密码重新登录。</p>
        </div>
      </div>
      <form class="account-password-form" @submit.prevent="changePassword">
        <label class="field">
          <span>当前密码</span>
          <SecretInput
            v-model="passwordForm.currentPassword"
            autocomplete="current-password"
            placeholder="输入当前密码"
          />
        </label>
        <div class="account-password-grid">
          <label class="field">
            <span>新密码</span>
            <SecretInput
              v-model="passwordForm.newPassword"
              autocomplete="new-password"
              placeholder="至少 8 个字符"
            />
          </label>
          <label class="field">
            <span>确认新密码</span>
            <SecretInput
              v-model="passwordForm.confirmPassword"
              autocomplete="new-password"
              placeholder="再次输入新密码"
            />
          </label>
        </div>
        <div class="account-password-foot">
          <small class="account-field-note">建议使用 12 个字符以上，并避免复用其他服务密码。</small>
          <button type="submit" class="button primary" :disabled="passwordSaving">
            {{ passwordSaving ? '更新中…' : '更新密码' }}
          </button>
        </div>
      </form>
    </section>

    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">关闭</button>
    </template>
  </Modal>
</template>
