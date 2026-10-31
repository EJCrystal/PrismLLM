<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ChannelQuotaSettings, ChannelQuotaSettingsRequest, ChannelQuotaSnapshot, QuotaProvider } from '../types/api'
import { buildQuotaSettings, buildNewApiQuotaSettings, quotaBalance, quotaStatus } from '../utils/channelQuota'
import { formatTime } from '../utils/format'
import Modal from './Modal.vue'
import SecretInput from './SecretInput.vue'

const props = defineProps<{ channel: Channel }>()
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved', channel: Channel): void
}>()
const providers = ref<QuotaProvider[]>([])
const loading = ref(true)
const saving = ref(false)
const querying = ref(false)
const error = ref('')
const result = ref<ChannelQuotaSnapshot | null>(null)
const targetKeyId = ref(props.channel.quota?.providerId ? '' : props.channel.keys?.[0]?.id || '')
const selectedKey = computed(() => props.channel.keys?.find((key) => key.id === targetKeyId.value))
const currentQuota = computed(() => targetKeyId.value ? selectedKey.value?.quota : props.channel.quota)
const providerId = ref('')
const newApiMode = ref<'system' | 'token'>('system')
const userId = ref('')
const credential = ref('')
const credentialSet = ref(false)
const clearCredential = ref(false)
function editableConfig(config: ChannelQuotaSettings['config']): ChannelQuotaSettings['config'] {
  if (providerId.value !== 'new-api') return config
  const { authMode, userId, ...other } = config
  return other
}
const configText = ref('{}')
const systemMode = computed(() => providerId.value === 'new-api' && newApiMode.value === 'system')
function buildSettings(): ChannelQuotaSettingsRequest {
  if (providerId.value === 'new-api') return buildNewApiQuotaSettings(configText.value, {
    mode: newApiMode.value, userId: userId.value, credential: credential.value, clearCredential: clearCredential.value,
  })
  const settings = buildQuotaSettings(providerId.value, configText.value)
  // 保留其他插件已通过 API 配置的独立凭据来源。
  if (providerId.value === currentQuota.value?.providerId && currentQuota.value?.credentialSource === 'DEDICATED') {
    settings.credentialSource = 'DEDICATED'
  }
  return settings
}
function settingsSignature() {
  try { return JSON.stringify(buildSettings()) } catch { return null }
}
const savedSettings = ref<string | null>(settingsSignature())
function resetForm() {
  const quota = currentQuota.value
  providerId.value = quota?.providerId || ''
  newApiMode.value = quota?.providerId === 'new-api' && quota.config?.authMode !== 'system' ? 'token' : 'system'
  userId.value = String(quota?.config?.userId || '')
  configText.value = JSON.stringify(editableConfig(quota?.config || {}), null, 2)
  credential.value = ''
  clearCredential.value = false
  credentialSet.value = Boolean(quota?.credentialSet)
  result.value = null
  error.value = ''
  savedSettings.value = settingsSignature()
}
resetForm()
watch(targetKeyId, resetForm)
const dirty = computed(() => {
  const signature = settingsSignature()
  return signature == null || signature !== savedSettings.value
})
const missing = computed(() => providerId.value && !providers.value.some((p) => p.id === providerId.value))
const state = computed(() => result.value ? quotaStatus(result.value) : null)
const attemptedKeys = computed(() => result.value?.attemptedKeys ?? [])

async function loadProviders() {
  error.value = ''
  loading.value = true
  try { providers.value = await api.listQuotaProviders() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { loading.value = false }
}

async function save() {
  error.value = ''
  saving.value = true
  try {
    const settings = buildSettings()
    if (systemMode.value && !credentialSet.value && !settings.credential && !settings.clearCredential) {
      throw new Error('请填写系统访问令牌')
    }
    const channel = targetKeyId.value
      ? await api.updateChannelKeyQuota(props.channel.id, targetKeyId.value, settings)
      : await api.updateChannelQuota(props.channel.id, settings)
    const quota = targetKeyId.value ? channel.keys.find((key) => key.id === targetKeyId.value)?.quota : channel.quota
    if (!quota) throw new Error('保存成功，但未返回目标密钥的额度配置')
    configText.value = JSON.stringify(editableConfig(quota.config), null, 2)
    credential.value = ''
    clearCredential.value = false
    credentialSet.value = Boolean(quota.credentialSet)
    savedSettings.value = settingsSignature()
    result.value = null
    emit('saved', channel)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { saving.value = false }
}

async function query() {
  querying.value = true
  error.value = ''
  result.value = null
  try { result.value = targetKeyId.value
    ? await api.queryChannelKeyQuota(props.channel.id, targetKeyId.value)
    : await api.queryChannelQuota(props.channel.id) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { querying.value = false }
}

onMounted(loadProviders)
</script>

<template>
  <Modal :title="'渠道额度 · ' + channel.name" @close="$emit('close')">
    <div class="quota-panel">
      <p class="muted small">按密钥配置其所属上游账户，分别查询余额。同一账户的多个密钥可能显示相同余额，请勿相加。</p>
      <label class="field">
        <span>查询对象</span>
        <select v-model="targetKeyId" :disabled="saving || querying || dirty">
          <option v-for="key in channel.keys" :key="key.id" :value="key.id">{{ key.name }}{{ key.quota?.providerId ? '（已配置）' : '（未启用）' }}</option>
          <option value="">渠道配置（原有）</option>
        </select>
        <small v-if="selectedKey">此密钥独立保存账户配置和查询令牌。查询失败时不会切换到其他密钥或使用渠道级账户。</small>
        <small v-else>保留原有渠道级查询；多个账户请在上方选择具体密钥分别配置。</small>
      </label>
      <label class="field">
        <span>额度查询插件</span>
        <select v-model="providerId" :disabled="loading || saving || querying">
          <option value="">不启用</option>
          <option v-if="missing" :value="providerId">{{ providerId }}（未加载）</option>
          <option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.displayName }}</option>
        </select>
      </label>
      <p v-if="loading" class="muted small" role="status">正在读取已安装插件…</p>
      <p v-else-if="providers.length === 0" class="callout">
        尚未加载额度插件。将可信插件 JAR 放入服务器的 plugins 目录后重启网关；自定义目录可设置 QUOTA_PLUGIN_DIR。
        <button type="button" class="button ghost mini" @click="loadProviders">重新读取</button>
      </p>
      <p v-if="missing" class="callout warning">选中的插件尚未加载，请检查服务器插件目录和启动日志。</p>
      <template v-if="providerId === 'new-api'">
        <label class="field">
          <span>查询认证方式</span>
          <select v-model="newApiMode" :disabled="saving || querying">
            <option value="system">系统访问令牌 + 用户 ID</option>
            <option value="token">渠道模型密钥（需要上游支持账户额度）</option>
          </select>
        </label>
        <template v-if="systemMode">
          <label class="field">
            <span>New API 用户 ID</span>
            <input v-model="userId" type="text" inputmode="numeric" maxlength="19" placeholder="例如 123" :disabled="saving || querying" />
            <small>填写系统访问令牌所属账户的用户 ID，必须与上游账户一致。</small>
          </label>
          <label class="field" for="quota-system-token">
            <span>系统访问令牌 <span v-if="credentialSet && !clearCredential" class="badge success">已保存</span></span>
            <SecretInput id="quota-system-token" v-model="credential" :placeholder="credentialSet ? '留空保留原令牌，输入新令牌可替换' : '填写系统访问令牌，不含 Bearer 前缀'" autocomplete="new-password" :disabled="saving || querying || clearCredential" />
            <small>从上游账户设置中生成。令牌单独加密保存，仅用于额度查询。</small>
          </label>
          <div v-if="credentialSet">
            <button type="button" class="button ghost mini" :disabled="saving || querying || Boolean(credential)" @click="clearCredential = !clearCredential">{{ clearCredential ? '撤销清除' : '清除已保存令牌' }}</button>
            <span v-if="clearCredential" class="muted small">保存设置后清除令牌。</span>
          </div>
        </template>
        <p v-else class="muted small">仅适用于会返回账户额度的上游版本。若提示不支持，请改用系统访问令牌 + 用户 ID。</p>
      </template>
      <details v-if="providerId" class="quota-config">
        <summary>插件配置（可选）</summary>
        <label class="field">
          <span>JSON 配置</span>
          <textarea v-model="configText" rows="5" spellcheck="false" :disabled="saving || querying" aria-label="额度插件 JSON 配置" />
          <small>一般保留空对象即可。子路径部署可填写 baseUrl 指定同站点根地址。这里仅保存非敏感参数，上游凭据由网关提供。</small>
        </label>
      </details>
      <div v-if="dirty" class="quota-unsaved">
        <p class="muted small">配置已修改，请保存后查询。保存或放弃修改后可切换查询对象。</p>
        <button type="button" class="button ghost mini" :disabled="saving || querying" @click="resetForm">放弃修改</button>
      </div>
      <p v-if="error" class="callout danger" role="alert">{{ error }}</p>
      <div v-if="querying" class="callout" role="status">正在查询上游账户额度…</div>
      <section v-else-if="result && !dirty" class="quota-result" aria-live="polite">
        <div class="quota-result-head">
          <span>上游账户剩余额度</span>
          <span class="badge" :class="state?.className || 'neutral'">{{ state?.label || '未知状态' }}</span>
        </div>
        <strong class="quota-balance">{{ quotaBalance(result) }}</strong>
        <dl class="quota-facts">
          <template v-if="result.targetKeyId">
            <dt>所属密钥</dt><dd>{{ selectedKey?.name }}<small class="muted quota-key-id">ID：{{ result.targetKeyId }}</small></dd>
          </template>
          <template v-if="result.credentialSource === 'DEDICATED'">
            <dt>查询凭据</dt><dd>{{ result.providerId === 'new-api' && currentQuota?.config.authMode === 'system' ? '系统访问令牌' : '独立查询凭据' }}</dd>
            <template v-if="result.providerId === 'new-api' && currentQuota?.config.userId">
              <dt>账户用户 ID</dt><dd>{{ currentQuota.config.userId }}</dd>
            </template>
          </template>
          <template v-if="result.queryKey">
            <dt>查询密钥</dt>
            <dd>
              <span>{{ result.queryKey.name }}</span>
              <small class="muted quota-key-id">ID：{{ result.queryKey.id }}</small>
            </dd>
          </template>
          <template v-if="attemptedKeys.length > 1">
            <dt>查询顺序</dt>
            <dd>{{ attemptedKeys.map((key) => key.name).join(' → ') }}<small class="muted quota-key-id">前一密钥认证失败后回退</small></dd>
          </template>
          <template v-if="result.used != null"><dt>已用</dt><dd>{{ result.used }} {{ result.unit }}</dd></template>
          <template v-if="result.limit != null"><dt>总额度</dt><dd>{{ result.limit }} {{ result.unit }}</dd></template>
          <template v-if="result.planName"><dt>套餐</dt><dd>{{ result.planName }}</dd></template>
          <dt>查询时间</dt><dd>{{ formatTime(result.checkedAt) }}</dd>
        </dl>
        <p v-if="result.message" class="small" :class="'text-' + (state?.className || 'neutral')">{{ result.message }}</p>
        <p class="muted small">{{ result.cached ? '已使用最近查询的缓存。' : '' }}结果缓存 60 秒，再次查询会复用有效缓存。</p>
      </section>
    </div>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">关闭</button>
      <button type="button" class="button subtle" :disabled="!dirty || saving || querying || loading" @click="save">{{ saving ? '保存中…' : '保存设置' }}</button>
      <button type="button" class="button primary" :disabled="!providerId || dirty || missing || saving || querying || loading || (systemMode && !credentialSet)" @click="query">{{ querying ? '查询中…' : '查询额度' }}</button>
    </template>
  </Modal>
</template>

<style scoped>
.quota-panel { display: grid; gap: 16px; min-width: 0; }
.quota-panel p { margin: 0; overflow-wrap: anywhere; }
.quota-unsaved { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.quota-config summary { cursor: pointer; margin-bottom: 12px; }
.quota-config textarea { font-family: var(--font-mono, monospace); width: 100%; }
.quota-result { border-top: 1px solid var(--line); padding-top: 18px; display: grid; gap: 14px; }
.quota-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.quota-balance { font-size: 28px; line-height: 1.3; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.quota-facts { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 9px 20px; margin: 0; font-size: 13px; }
.quota-facts dt { color: var(--muted); }
.quota-facts dd { margin: 0; text-align: right; overflow-wrap: anywhere; }
.quota-key-id { display: block; margin-top: 4px; font-size: 11px; line-height: 1.5; }
</style>
