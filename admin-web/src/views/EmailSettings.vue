<template>
  <div class="page-container mail-page">
    <div class="page-head">
      <div>
        <h2>邮件服务</h2>
        <div class="sub">
          配置发信通道（SMTP / Resend / bondmail）与邮件模板 · 未配置时注册验证码、找回密码、管理员重置密码均不可用
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="endpointMissing"
      type="warning"
      show-icon
      class="mb16"
      message="邮件服务接口不可用（404）"
      description="GET /api/admin/mail/settings 返回 404，通常是后端未部署到该版本。本页已按接口契约完成对接：接口可用后点「刷新」即可生效；保存、测试发信会原样显示后端返回的错误，不会伪造成功。"
    />

    <a-skeleton v-if="loading && !settings" active :paragraph="{ rows: 8 }" />

    <template v-else>
      <div v-if="!endpointMissing" class="status-banner" :class="statusClass">
        <div class="status-main">
          <span class="status-dot" />
          <div>
            <div class="status-title">{{ statusTitle }}</div>
            <div class="status-sub">{{ statusSub }}</div>
          </div>
        </div>
        <div class="status-side">
          <a-tag v-if="settings?.lastTest" :color="settings.lastTest.ok ? 'success' : 'error'">
            自检 {{ settings.lastTest.ok ? 'OK' : '失败' }} · {{ formatDateTime(settings.lastTest.at) }}
          </a-tag>
          <a-tag v-else>尚未自检</a-tag>
        </div>
      </div>

      <div class="mail-settings-wrap">
          <div class="mail-grid">
            <div class="content-card form-card">
              <a-form layout="vertical" :model="form">
                <a-form-item label="发信方式">
                  <a-radio-group v-model:value="form.provider" button-style="solid">
                    <a-radio-button v-for="p in PROVIDERS" :key="p.value" :value="p.value">
                      {{ p.label }}
                    </a-radio-button>
                  </a-radio-group>
                  <div class="hint">{{ providerHint }}</div>
                </a-form-item>

                <template v-if="form.provider !== 'off'">
                  <a-divider orientation="left" plain>发件人</a-divider>
                  <a-row :gutter="16">
                    <a-col :xs="24" :md="12">
                      <a-form-item label="发件人地址" required>
                        <a-input v-model:value="form.from.address" placeholder="noreply@example.com" allow-clear />
                      </a-form-item>
                    </a-col>
                    <a-col :xs="24" :md="12">
                      <a-form-item label="发件人名称">
                        <a-input v-model:value="form.from.name" placeholder="Bond" allow-clear />
                      </a-form-item>
                    </a-col>
                  </a-row>
                </template>

                <template v-if="form.provider === 'smtp'">
                  <a-divider orientation="left" plain>SMTP 服务器</a-divider>
                  <a-row :gutter="16">
                    <a-col :xs="24" :md="15">
                      <a-form-item label="主机" required>
                        <a-input v-model:value="form.smtp.host" placeholder="smtp.example.com" allow-clear />
                      </a-form-item>
                    </a-col>
                    <a-col :xs="24" :md="9">
                      <a-form-item label="端口" required>
                        <a-input-number
                          v-model:value="form.smtp.port"
                          :min="1"
                          :max="65535"
                          style="width: 100%"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-row :gutter="16">
                    <a-col :xs="24" :md="12">
                      <a-form-item label="用户名">
                        <a-input v-model:value="form.smtp.username" placeholder="no-reply@example.com" allow-clear />
                      </a-form-item>
                    </a-col>
                    <a-col :xs="24" :md="12">
                      <a-form-item label="密码">
                        <a-input-password
                          v-model:value="form.smtp.password"
                          :placeholder="passwordPlaceholder"
                          autocomplete="new-password"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-row :gutter="16">
                    <a-col :xs="24" :md="12">
                      <a-form-item label="加密方式">
                        <a-radio-group v-model:value="encryption">
                          <a-radio-button value="ssl">SSL（465）</a-radio-button>
                          <a-radio-button value="starttls">STARTTLS（587）</a-radio-button>
                          <a-radio-button value="none">无加密</a-radio-button>
                        </a-radio-group>
                        <div class="hint">SSL 与 STARTTLS 互斥，保存时以后端校验结果为准。</div>
                      </a-form-item>
                    </a-col>
                    <a-col :xs="12" :md="6">
                      <a-form-item label="鉴权">
                        <a-switch v-model:checked="form.smtp.auth" />
                      </a-form-item>
                    </a-col>
                    <a-col :xs="12" :md="6">
                      <a-form-item label="超时（秒）">
                        <a-input-number v-model:value="form.smtp.timeoutSeconds" :min="1" :max="120" style="width: 100%" />
                      </a-form-item>
                    </a-col>
                  </a-row>
                </template>

                <template v-else-if="form.provider === 'resend'">
                  <a-divider orientation="left" plain>Resend</a-divider>
                  <a-form-item label="API Key">
                    <a-input-password
                      v-model:value="form.resend.apiKey"
                      :placeholder="resendKeyPlaceholder"
                      autocomplete="new-password"
                    />
                  </a-form-item>
                  <a-form-item label="Endpoint">
                    <a-input v-model:value="form.resend.endpoint" placeholder="https://api.resend.com" allow-clear />
                  </a-form-item>
                </template>

                <template v-else-if="form.provider === 'bondmail'">
                  <a-divider orientation="left" plain>bondmail</a-divider>
                  <a-form-item label="Endpoint">
                    <a-input v-model:value="form.bondmail.endpoint" placeholder="https://your-bondmail/api/v1/mail/send" allow-clear />
                  </a-form-item>
                </template>

                <a-alert
                  v-else
                  type="info"
                  show-icon
                  style="margin-bottom: 16px"
                  message="发信已关闭（off）"
                  description="注册验证码、找回密码、管理员重置密码等邮件都不会发送；接到发信服务的用户请求会收到「发信服务未配置」。"
                />

                <a-alert v-if="formError" type="error" show-icon class="mb16" :message="formError">
                  <template #action>
                    <a-button size="small" @click="load">重试</a-button>
                  </template>
                </a-alert>

                <div class="form-actions">
                  <a-button type="primary" :loading="saving" @click="save">保存设置</a-button>
                  <a-button @click="openTest">发送测试邮件</a-button>
                  <span class="grow" />
                  <span class="muted small">密码 / API Key 留空保存 = 不修改</span>
                </div>
              </a-form>
            </div>

            <div class="content-card side-card">
              <div class="section-title">
                <h3>当前状态</h3>
              </div>
              <div class="kv"><span>发信方式</span><b>{{ providerLabel(settings?.provider) }}</b></div>
              <div class="kv">
                <span>配置状态</span>
                <a-tag :color="settings?.configured ? 'success' : 'default'">
                  {{ settings?.configured ? '已配置' : '未配置' }}
                </a-tag>
              </div>
              <div class="kv">
                <span>发件人</span>
                <b class="mono break">{{ settings?.from?.address || '—' }}</b>
              </div>
              <div class="kv" v-if="settings?.smtp?.host">
                <span>SMTP</span>
                <b class="mono break">{{ settings.smtp.host }}:{{ settings.smtp.port }}</b>
              </div>
              <div class="kv" v-if="settings">
                <span>口令 / Key</span>
                <b class="mono">{{ credentialSummary }}</b>
              </div>

              <a-divider class="tight" />

              <div class="section-title">
                <h3>测试发信</h3>
              </div>
              <p class="muted small side-text">
                保存后点「发送测试邮件」验证连通性。失败时这里会显示后端返回的下游错误摘要（如认证失败、连接超时），不会回显密码。
              </p>
              <a-button block @click="openTest">发送测试邮件</a-button>

              <a-divider class="tight" />

              <div class="section-title">
                <h3>提示</h3>
              </div>
              <ul class="tip-list">
                <li>465 端口用 SSL；587 端口用 STARTTLS。</li>
                <li>密码在数据库中加密保存，接口只返回掩码。</li>
                <li>设置保存后立即生效（网关缓存 60s 内失效）。</li>
                <li>模板与样式在「邮件模板」页面维护（左侧菜单 → 系统 → 邮件模板）。</li>
              </ul>
            </div>
          </div>
      </div>
    </template>

    <!-- ─────────── 测试发信弹窗 ─────────── -->
    <a-modal
      v-model:open="testOpen"
      title="发送测试邮件"
      :confirm-loading="testSending"
      ok-text="发送"
      cancel-text="关闭"
      @ok="doTest"
    >
      <a-form layout="vertical">
        <a-form-item
          label="收件地址"
          :validate-status="testToError ? 'error' : undefined"
          :help="testToError"
        >
          <a-input v-model:value="testTo" placeholder="delivered@resend.dev" allow-clear @press-enter="doTest" />
        </a-form-item>
      </a-form>
      <a-alert
        v-if="testResult"
        :type="testResult.ok ? 'success' : 'error'"
        show-icon
        :message="testResult.ok ? '测试邮件已发送' : '发送失败'"
        :description="testResult.message || undefined"
      />
      <div v-if="!settings?.configured" class="muted small" style="margin-top: 10px">
        当前发信方式为「关闭」或尚未保存配置：后端会返回「发信服务未配置」之类的错误信息，这里会原样展示。
      </div>
      <div class="muted small" style="margin-top: 10px">
        自测建议用发信服务允许的测试收件地址（Resend 账号：delivered@resend.dev）；失败时原样展示下游返回的 message，本页不做任何成功伪装。
      </div>
    </a-modal>

  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import CodeEditor from '../components/CodeEditor.vue'
import { ENDPOINT_MISSING_TEXT, errorText, isEndpointMissing } from '../utils/errors'
import { formatDateTime, fromNow } from '../utils/format'
import type {
  EmailSettings,
  EmailSettingsPayload,
  EmailTemplate,
  EmailTemplateCreate,
  EmailTemplateUpsert,
  EmailTestResult,
  MailPurpose
} from '../types'

type EditorRef = { insertAtCursor: (t: string) => void; focus: () => void }

const PROVIDERS = [
  { value: 'smtp', label: 'SMTP', hint: '自建或第三方 SMTP：465 走 SSL，587 走 STARTTLS，也可用明文 25。' },
  { value: 'resend', label: 'Resend', hint: '通过 Resend 的 HTTP API 直连发信，需要有效的 API Key。' },
  { value: 'bondmail', label: 'bondmail', hint: 'bondmail 用户态接口（Basic 认证），调用 /v1/mail/send。' },
  { value: 'off', label: '关闭', hint: '不发送任何邮件：注册验证码、找回密码、重置密码都会失败。' }
]

const PROVIDER_LABELS: Record<string, string> = {
  smtp: 'SMTP',
  resend: 'Resend',
  bondmail: 'bondmail',
  off: '关闭'
}

/** 变量 → 示例值（仅用于预览渲染） */
const SAMPLE_VALUES: Record<string, string> = {
  code: '123456',
  username: '张三',
  email: 'zhangsan@example.com',
  expiresMinutes: '10',
  appName: 'Bond',
  supportEmail: 'support@example.com',
  actionUrl: 'https://bond.example.com/verify?token=demo',
  year: String(new Date().getFullYear())
}

const tab = ref('settings')
const loading = ref(false)
const endpointMissing = ref(false)
const settings = ref<EmailSettings | null>(null)
const formError = ref('')
const saving = ref(false)

const form = reactive<EmailSettingsPayload>({
  provider: 'off',
  from: { address: '', name: '' },
  smtp: {
    host: '',
    port: 465,
    username: '',
    password: '',
    auth: true,
    starttls: false,
    ssl: true,
    timeoutSeconds: 10
  },
  resend: { apiKey: '', endpoint: 'https://api.resend.com' },
  bondmail: { endpoint: '' }
})

const encryption = computed({
  get: () => (form.smtp.ssl ? 'ssl' : form.smtp.starttls ? 'starttls' : 'none'),
  set: (v: string) => {
    form.smtp.ssl = v === 'ssl'
    form.smtp.starttls = v === 'starttls'
    if (v === 'ssl' && form.smtp.port === 587) form.smtp.port = 465
    if (v === 'starttls' && form.smtp.port === 465) form.smtp.port = 587
  }
})

const providerHint = computed(
  () => PROVIDERS.find((p) => p.value === form.provider)?.hint || ''
)
const passwordPlaceholder = computed(() =>
  settings.value?.smtp?.passwordMasked
    ? `当前：${settings.value.smtp.passwordMasked}（留空不修改）`
    : '未设置（留空不修改）'
)
const resendKeyPlaceholder = computed(() =>
  settings.value?.resend?.apiKeyMasked
    ? `当前：${settings.value.resend.apiKeyMasked}（留空不修改）`
    : 're_...（未设置）'
)

/** 侧栏展示：口令只显示「已设置 + 尾 4 位掩码」，绝不显示明文 */
const credentialSummary = computed(() => {
  const s = settings.value
  if (!s) return '—'
  if (s.provider === 'smtp') {
    return s.smtp?.passwordSet
      ? `已设置 ${s.smtp.passwordMasked || ''}`.trim()
      : '未设置'
  }
  if (s.provider === 'resend') {
    return s.resend?.apiKeySet ? `已设置 ${s.resend.apiKeyMasked || ''}`.trim() : '未设置'
  }
  return '—'
})

const statusClass = computed(() => {
  if (!settings.value) return 'warn'
  if (!settings.value.configured) return 'warn'
  if (settings.value.lastTest && !settings.value.lastTest.ok) return 'err'
  return 'ok'
})

const statusTitle = computed(() => {
  if (!settings.value) return '尚未加载到发信配置'
  if (!settings.value.configured) return '未配置发信服务：注册验证码、找回密码、管理员重置密码将不可用'
  return `已配置发信 · ${providerLabel(settings.value.provider)}`
})

const statusSub = computed(() => {
  const s = settings.value
  if (!s) return '后端接口未就绪或加载失败，表单显示的是本地默认值。'
  if (!s.configured) {
    return s.provider === 'off'
      ? '当前发信方式为「关闭」。在左侧选择 SMTP / Resend / bondmail 并保存后即可启用。'
      : '发信方式已选择，但配置不完整或未保存，保存后状态会更新。'
  }
  const parts = [`发件人 ${s.from?.address || '未设置'}`]
  if (s.lastTest) {
    parts.push(
      `最近自检 ${s.lastTest.ok ? '成功' : '失败'}${s.lastTest.message ? '：' + s.lastTest.message : ''}`
    )
  } else {
    parts.push('尚未执行过自检，建议发一封测试邮件')
  }
  return parts.join(' · ')
})

function providerLabel(p?: string | null): string {
  if (!p) return '—'
  return PROVIDER_LABELS[p] || p
}

function applySettings(s: EmailSettings) {
  settings.value = s
  form.provider = s.provider || 'off'
  form.from.address = s.from?.address || ''
  form.from.name = s.from?.name || ''
  form.smtp.host = s.smtp?.host || ''
  form.smtp.port = s.smtp?.port ?? 465
  form.smtp.username = s.smtp?.username || ''
  form.smtp.password = ''
  form.smtp.auth = s.smtp?.auth ?? true
  form.smtp.starttls = !!s.smtp?.starttls
  form.smtp.ssl = !!s.smtp?.ssl
  form.smtp.timeoutSeconds = s.smtp?.timeoutSeconds ?? 10
  form.resend.apiKey = ''
  form.resend.endpoint = s.resend?.endpoint || 'https://api.resend.com'
  form.bondmail.endpoint = s.bondmail?.endpoint || ''
}

async function load() {
  loading.value = true
  try {
    const res = await adminApi.getEmailSettings()
    if (res.code === 200 && res.data) {
      applySettings(res.data)
      endpointMissing.value = false
      formError.value = ''
    } else {
      formError.value = res.message || '加载邮件服务设置失败'
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
    } else {
      formError.value = errorText(err, '加载邮件服务设置失败')
      message.error(formError.value)
    }
  } finally {
    loading.value = false
  }
}

function validateForm(): string {
  if (form.provider === 'off') return ''
  if (!form.from.address.trim()) return '请填写发件人地址'
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(form.from.address.trim())) {
    return '发件人地址格式不正确'
  }
  if (form.provider === 'smtp') {
    if (!form.smtp.host.trim()) return 'SMTP 主机不能为空'
    if (typeof form.smtp.port !== 'number' || form.smtp.port < 1 || form.smtp.port > 65535) {
      return 'SMTP 端口需在 1–65535 之间'
    }
    if (form.smtp.ssl && form.smtp.starttls) return 'SSL 与 STARTTLS 不能同时启用'
    if (form.smtp.auth && !form.smtp.username.trim()) return '启用鉴权时必须填写用户名'
    if (typeof form.smtp.timeoutSeconds !== 'number' || form.smtp.timeoutSeconds < 1) {
      return '超时时间需大于 0 秒'
    }
  }
  if (form.provider === 'resend' && !form.resend.apiKey && !settings.value?.resend?.apiKeyMasked) {
    return '请填写 Resend API Key（已有掩码时留空表示不修改）'
  }
  if (form.provider === 'bondmail' && !form.bondmail.endpoint.trim()) {
    return '请填写 bondmail endpoint'
  }
  return ''
}

function buildPayload(): EmailSettingsPayload {
  const payload: EmailSettingsPayload = {
    provider: form.provider,
    from: { address: form.from.address.trim(), name: form.from.name.trim() },
    smtp: {
      host: form.smtp.host.trim(),
      port: Number(form.smtp.port),
      username: form.smtp.username.trim(),
      auth: form.smtp.auth,
      starttls: form.smtp.starttls,
      ssl: form.smtp.ssl,
      timeoutSeconds: Number(form.smtp.timeoutSeconds)
    },
    resend: { endpoint: form.resend.endpoint.trim() },
    bondmail: { endpoint: form.bondmail.endpoint.trim() }
  }
  if (form.smtp.password) payload.smtp.password = form.smtp.password
  if (form.resend.apiKey) payload.resend.apiKey = form.resend.apiKey
  return payload
}

async function save() {
  const err = validateForm()
  if (err) {
    formError.value = err
    message.warning(err)
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const res = await adminApi.updateEmailSettings(buildPayload())
    if (res.code === 200) {
      if (res.data) applySettings(res.data)
      message.success('邮件服务设置已保存')
      endpointMissing.value = false
    } else {
      formError.value = res.message || '保存失败'
      message.error(formError.value)
    }
  } catch (e) {
    if (isEndpointMissing(e)) {
      endpointMissing.value = true
      message.warning(ENDPOINT_MISSING_TEXT)
    }
    formError.value = errorText(e, '保存失败')
    message.error(formError.value)
  } finally {
    saving.value = false
  }
}

// ── 测试发信 ──
const testOpen = ref(false)
const testTo = ref('')
const testToError = ref('')
const testSending = ref(false)
const testResult = ref<EmailTestResult | null>(null)

function openTest() {
  testResult.value = null
  testToError.value = ''
  testOpen.value = true
}

/** toast 只展示摘要，完整错误保留在弹窗/抽屉里 */
function shorten(text: string, max = 100) {
  return text.length > max ? text.slice(0, max) + '…' : text
}

async function doTest() {
  const to = testTo.value.trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(to)) {
    testToError.value = '请输入有效的收件地址'
    return
  }
  testToError.value = ''
  testSending.value = true
  try {
    const res = await adminApi.testEmail(to)
    if (res.code === 200 && res.data) {
      testResult.value = res.data
      if (res.data.ok) message.success('测试邮件已发送')
      else message.error(shorten(res.data.message || '发送失败'))
    } else {
      testResult.value = { ok: false, message: res.message || '发送失败' }
      message.error(shorten(testResult.value.message))
    }
  } catch (e) {
    if (isEndpointMissing(e)) {
      endpointMissing.value = true
      message.warning(ENDPOINT_MISSING_TEXT)
    }
    testResult.value = { ok: false, message: errorText(e, '发送失败') }
    message.error(shorten(testResult.value.message))
  } finally {
    testSending.value = false
  }
}

// ── 用途绑定（task-76 / V17）──
const purposes = ref<MailPurpose[]>([])
const purposesLoading = ref(false)
const purposeError = ref('')
const bindingPurpose = ref('')
const unbindingPurpose = ref('')

async function loadPurposes() {
  purposesLoading.value = true
  purposeError.value = ''
  try {
    const res = await adminApi.listMailPurposes()
    if (res.code === 200 && res.data) {
      purposes.value = res.data
    } else {
      purposeError.value = res.message || '用途绑定数据加载失败'
    }
  } catch (e) {
    if (isEndpointMissing(e)) {
      purposeError.value = '用途绑定接口未上线（GET /api/admin/mail/purposes 返回 404），后端部署后这里会显示 6 个用途。'
    } else {
      purposeError.value = errorText(e, '用途绑定数据加载失败')
    }
  } finally {
    purposesLoading.value = false
  }
}

/** purpose → 中文名 */
const purposeLabelMap = computed(() => {
  const map = new Map<string, string>()
  for (const p of purposes.value) map.set(p.purpose, p.label || p.purpose)
  return map
})

/** 模板 code → 绑定了它的用途名列表（boundTemplateCode 精确匹配） */
const boundPurposesByCode = computed(() => {
  const map = new Map<string, string[]>()
  for (const p of purposes.value) {
    if (!p.boundTemplateCode) continue
    const list = map.get(p.boundTemplateCode) || []
    list.push(p.label || p.purpose)
    map.set(p.boundTemplateCode, list)
  }
  return map
})

/** 当前「真正生效」的模板 code 集合（内置默认 + 已绑定）；用于判断自定义模板是否被任何用途使用 */
const templatesUsedBy = computed(() => {
  const set = new Set<string>()
  for (const p of purposes.value) if (p.effectiveTemplateCode) set.add(p.effectiveTemplateCode)
  return set
})

const templateOptions = computed(() =>
  templates.value.map((t) => ({
    value: t.code,
    label: `${t.name || t.code}（${t.code}）${t.isBuiltin ? '' : ' · 自定义'}`,
    disabled: !t.enabled
  }))
)

async function bindPurpose(p: MailPurpose, code: string) {
  if (!code || code === p.boundTemplateCode) return
  const tpl = templates.value.find((t) => t.code === code)
  Modal.confirm({
    title: `将「${tpl?.name || code}」设为用途「${p.label || p.purpose}」`,
    content: `绑定后该用途的邮件将使用这个模板（变量需包含：${(p.requiredVariables || []).join('、') || '无'}）。原有模板不会被删除。`,
    okText: '确认绑定',
    cancelText: '取消',
    onOk: async () => {
      bindingPurpose.value = p.purpose
      try {
        await adminApi.bindMailPurpose(p.purpose, code)
        message.success(`已绑定：${p.label || p.purpose} → ${tpl?.name || code}`)
        await Promise.all([loadPurposes(), loadTemplates()])
      } catch (e) {
        message.error(shorten(isEndpointMissing(e) ? '用途绑定接口未上线（404）' : errorText(e, '绑定失败')))
      } finally {
        bindingPurpose.value = ''
      }
    }
  })
}

function onSetPurpose(tpl: EmailTemplate, purpose: string) {
  const p = purposes.value.find((x) => x.purpose === purpose)
  if (!p) return
  if (p.boundTemplateCode === tpl.code) {
    message.info('该模板已绑定到这个用途')
    return
  }
  bindPurpose(p, tpl.code)
}

/** 解绑并恢复内置默认（PUT /api/admin/mail/purposes/{purpose} {}） */
function unbindPurpose(p: MailPurpose) {
  Modal.confirm({
    title: `恢复「${p.label || p.purpose}」的默认模板`,
    content: `将解除与 ${p.boundTemplateCode || '当前模板'} 的绑定，恢复使用内置模板 ${p.builtinTemplateCode}。`,
    okText: '恢复默认',
    cancelText: '取消',
    onOk: async () => {
      unbindingPurpose.value = p.purpose
      try {
        await adminApi.bindMailPurpose(p.purpose, '')
        message.success(`已恢复默认模板：${p.label || p.purpose}`)
        await Promise.all([loadPurposes(), loadTemplates()])
      } catch (e) {
        message.error(shorten(isEndpointMissing(e) ? '用途绑定接口未上线（404）' : errorText(e, '恢复默认失败')))
      } finally {
        unbindingPurpose.value = ''
      }
    }
  })
}

/** 表格行「设为用途」下拉的菜单回调（$event 为 a-menu 的点击信息） */
function onPurposeMenu(e: any, tpl: EmailTemplate) {
  const purpose = typeof e?.key === 'string' ? e.key : ''
  if (purpose) onSetPurpose(tpl, purpose)
}

/** 表格行「更多」下拉：复制/重置/删除（按内置/自定义分流） */
function onMoreMenu(e: any, tpl: EmailTemplate) {
  const key = typeof e?.key === 'string' ? e.key : ''
  if (key === 'copy') copyTemplate(tpl)
  else if (key === 'reset') resetTemplate(tpl)
  else if (key === 'delete') deleteTemplate(tpl)
}

// ── 模板列表 ──
const templates = ref<EmailTemplate[]>([])
const templatesLoading = ref(false)
const templatesMissing = ref(false)
const templateError = ref('')
const togglingCode = ref('')
const copyingCode = ref('')
const builtinCount = computed(() => templates.value.filter((t) => t.isBuiltin).length)

const templateColumns = [
  { title: '模板', key: 'name', width: 260 },
  { title: '启用', key: 'enabled', width: 80 },
  { title: '类型', key: 'type', width: 90 },
  { title: '用途', key: 'purposes', width: 170 },
  { title: '更新时间', key: 'updatedAt', width: 170 },
  { title: '操作', key: 'actions', width: 250 }
]

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

async function loadTemplates() {
  templatesLoading.value = true
  templateError.value = ''
  try {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const res = await adminApi.listEmailTemplates()
        if (res.code === 200 && res.data) {
          const items = res.data
          templatesMissing.value = false
          // 列表接口只返回 code/name/enabled/isBuiltin/updatedAt；正文与变量白名单需逐个取详情（6 个内置模板，开销可接受）
          const details = await Promise.all(
            items.map(async (t) => {
              try {
                const d = await adminApi.getEmailTemplate(t.code)
                return d.code === 200 && d.data ? d.data : null
              } catch {
                return null
              }
            })
          )
          templates.value = items.map(
            (t, i) =>
              (details[i] ?? {
                ...t,
                subject: '',
                bodyHtml: '',
                bodyText: null,
                variables: [] as string[]
              }) as EmailTemplate
          )
          return
        }
        if (attempt === 0) {
          await delay(1200)
          continue
        }
        templateError.value = res.message || '模板列表加载失败'
      } catch (err) {
        if (isEndpointMissing(err)) {
          templatesMissing.value = true
          endpointMissing.value = true
          return
        }
        if (attempt < 2) {
          // 网关偶发超时/502：自动重试（最多 3 次）
          await delay(2000)
          continue
        }
        templateError.value = errorText(err, '模板列表加载失败')
        message.error(templateError.value)
      }
    }
  } finally {
    templatesLoading.value = false
  }
}

async function toggleEnabled(tpl: EmailTemplate, enabled: boolean) {
  const prev = tpl.enabled
  tpl.enabled = enabled
  togglingCode.value = tpl.code
  try {
    const detail = await adminApi.getEmailTemplate(tpl.code)
    const d = detail.data
    if (!d) throw new Error('模板详情为空')
    const body: EmailTemplateUpsert = {
      name: d.name,
      subject: d.subject,
      bodyHtml: d.bodyHtml,
      bodyText: d.bodyText,
      enabled
    }
    const res = await adminApi.updateEmailTemplate(tpl.code, body)
    if (res.code === 200) {
      message.success(enabled ? `已启用「${tpl.name}」` : `已停用「${tpl.name}」`)
    } else {
      tpl.enabled = prev
      message.error(res.message || '切换失败')
    }
  } catch (e) {
    tpl.enabled = prev
    message.error(isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, '切换失败'))
  } finally {
    togglingCode.value = ''
  }
}

// ── 模板编辑 ──
const drawerOpen = ref(false)
const creating = ref(false)
const saveError = ref('')
const editing = ref<EmailTemplate | null>(null)
const editLoading = ref(false)
const savingTemplate = ref(false)
const variables = ref<string[]>([])
const subjectRef = ref<{ input?: HTMLInputElement } | null>(null)
const bodyHtmlRef = ref<EditorRef | null>(null)
const bodyTextRef = ref<EditorRef | null>(null)
const focusField = ref<'subject' | 'bodyHtml' | 'bodyText'>('bodyHtml')
const focusLabel = computed(
  () => ({ subject: '主题', bodyHtml: 'HTML 正文', bodyText: '纯文本正文' })[focusField.value]
)

const editForm = reactive({ name: '', subject: '', bodyHtml: '', bodyText: '', enabled: true })

/** 新建模板时可用的变量白名单：内置模板已声明变量 ∪ 常见系统变量 */
const knownVariables = computed(() => {
  const set = new Set<string>(Object.keys(SAMPLE_VALUES))
  for (const t of templates.value) for (const v of t.variables || []) set.add(v)
  return Array.from(set)
})

const previewHtml = ref('')
const previewLoading = ref(false)
const previewLabel = ref('等待后端渲染')
/** 后端 task-76 起返回 missingVariables；字段不存在时保持空数组 */
const previewMissing = ref<string[]>([])
/** 后端 preview 返回的 warnings（渲染提示，如变量未知/兜底） */
const previewWarnings = ref<string[]>([])
let previewTimer: number | undefined

const sampleSummary = computed(() =>
  Object.keys(buildSampleVars())
    .slice(0, 5)
    .map((k) => `{{${k}}}=${SAMPLE_VALUES[k] ?? '示例'}`)
    .join('、')
)

function formatVar(name: string): string {
  return '{' + '{' + name + '}' + '}'
}

/** 预览用示例变量：只填我们确定语义的变量；其余交给后端 missingVariables 提示（不伪造示例值） */
function buildSampleVars(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const v of variables.value) {
    const sample = SAMPLE_VALUES[v]
    if (sample !== undefined) out[v] = sample
  }
  return out
}

/** 预览错误：后端渲染失败时原样展示，不再用本地拼 HTML 兜底冒充「渲染成功」 */
const previewError = ref('')

async function runPreview() {
  if (!editing.value) return
  previewMissing.value = []
  previewWarnings.value = []
  previewError.value = ''
  if (creating.value) {
    // 未保存的模板后端没有 code，无法渲染；如实提示，不本地拼 HTML
    previewHtml.value = ''
    previewLabel.value = '未保存的模板：保存后才能后端预览'
    return
  }
  previewLoading.value = true
  try {
    const res = await adminApi.previewEmail(editing.value.code, buildSampleVars())
    if (res.code === 200 && res.data?.html) {
      previewHtml.value = res.data.html
      previewLabel.value = '后端渲染'
      previewMissing.value = res.data.missingVariables || []
      previewWarnings.value = res.data.warnings || []
    } else {
      previewHtml.value = ''
      previewLabel.value = '后端渲染失败'
      previewError.value = res.message || '预览接口未返回 HTML'
    }
  } catch (e) {
    previewHtml.value = ''
    previewLabel.value = '后端渲染失败'
    previewError.value = isEndpointMissing(e) ? '预览接口未上线（404）' : errorText(e, '预览失败')
  } finally {
    previewLoading.value = false
  }
}

watch(
  () => [editForm.subject, editForm.bodyHtml, editForm.bodyText],
  () => {
    previewHtml.value = ''
    previewError.value = ''
    previewLabel.value = '等待后端渲染'
    previewMissing.value = []
    previewWarnings.value = []
    if (previewTimer) window.clearTimeout(previewTimer)
    previewTimer = window.setTimeout(() => {
      runPreview()
    }, 800)
  }
)

async function fetchTemplateDetail(code: string) {
  editLoading.value = true
  try {
    const res = await adminApi.getEmailTemplate(code)
    if (res.code === 200 && res.data) {
      const d = res.data
      editing.value = d
      variables.value = d.variables || []
      editForm.name = d.name || ''
      editForm.subject = d.subject || ''
      editForm.bodyHtml = d.bodyHtml || ''
      editForm.bodyText = d.bodyText || ''
      editForm.enabled = d.enabled !== false
    } else {
      message.error(res.message || '模板加载失败')
    }
  } catch (e) {
    if (isEndpointMissing(e)) {
      endpointMissing.value = true
      templatesMissing.value = true
    }
    message.error(isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, '模板加载失败'))
  } finally {
    editLoading.value = false
    await runPreview()
  }
}

async function openEdit(tpl: EmailTemplate) {
  drawerOpen.value = true
  creating.value = false
  saveError.value = ''
  previewMissing.value = []
  previewWarnings.value = []
  editing.value = tpl
  variables.value = tpl.variables || []
  editForm.name = tpl.name || ''
  editForm.subject = tpl.subject || ''
  editForm.bodyHtml = tpl.bodyHtml || ''
  editForm.bodyText = tpl.bodyText || ''
  editForm.enabled = tpl.enabled !== false
  focusField.value = 'bodyHtml'
  previewHtml.value = ''
  previewError.value = ''
  previewLabel.value = '等待后端渲染'
  await fetchTemplateDetail(tpl.code)
}

/** 新建自定义模板（POST /api/admin/mail/templates，code 由后端生成 custom_xxxxxxxx） */
function openCreate() {
  drawerOpen.value = true
  creating.value = true
  saveError.value = ''
  previewMissing.value = []
  previewWarnings.value = []
  editing.value = null
  variables.value = knownVariables.value
  editForm.name = ''
  editForm.subject = ''
  editForm.bodyHtml = ''
  editForm.bodyText = ''
  editForm.enabled = true
  focusField.value = 'bodyHtml'
  previewHtml.value = ''
  previewError.value = ''
  previewLabel.value = '未保存的模板：保存后才能后端预览'
}

function insertVar(name: string) {
  const snippet = `{{${name}}}`
  if (focusField.value === 'subject' && subjectRef.value?.input) {
    const el = subjectRef.value.input
    const start = el.selectionStart ?? editForm.subject.length
    const end = el.selectionEnd ?? start
    editForm.subject = editForm.subject.slice(0, start) + snippet + editForm.subject.slice(end)
    nextTick(() => {
      el.focus()
      const pos = start + snippet.length
      el.setSelectionRange(pos, pos)
    })
    return
  }
  const target = focusField.value === 'bodyText' ? bodyTextRef.value : bodyHtmlRef.value
  target?.insertAtCursor(snippet)
}

async function saveTemplate() {
  if (!editing.value && !creating.value) return
  saveError.value = ''
  const name = editForm.name.trim()
  const subject = editForm.subject.trim()
  if (!name) {
    message.warning('模板名称不能为空')
    return
  }
  if (!subject) {
    message.warning('主题不能为空')
    return
  }
  if (!editForm.bodyHtml.trim()) {
    message.warning('HTML 正文不能为空')
    return
  }
  savingTemplate.value = true
  try {
    if (creating.value) {
      const body: EmailTemplateCreate = {
        name,
        subject,
        bodyHtml: editForm.bodyHtml,
        bodyText: editForm.bodyText ? editForm.bodyText : null,
        enabled: editForm.enabled
      }
      const res = await adminApi.createEmailTemplate(body)
      if (res.code === 200 && res.data) {
        message.success(`已创建模板 ${res.data.code}`)
        creating.value = false
        editing.value = res.data
        variables.value = res.data.variables || variables.value
        await loadTemplates()
      } else {
        saveError.value = res.message || `接口返回 code=${res.code}`
        message.error(shorten(saveError.value) + '（完整错误见抽屉内提示）')
      }
      return
    }
    const body: EmailTemplateUpsert = {
      name,
      subject,
      bodyHtml: editForm.bodyHtml,
      bodyText: editForm.bodyText ? editForm.bodyText : null,
      enabled: editForm.enabled
    }
    const res = await adminApi.updateEmailTemplate(editing.value!.code, body)
    if (res.code === 200) {
      message.success('模板已保存')
      if (res.data) {
        editing.value = res.data
        variables.value = res.data.variables || variables.value
      }
      await loadTemplates()
    } else {
      saveError.value = res.message || `接口返回 code=${res.code}`
      message.error(shorten(saveError.value) + '（完整错误见抽屉内提示）')
    }
  } catch (e) {
    saveError.value = isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, creating.value ? '创建失败' : '保存失败')
    message.error(shorten(saveError.value) + '（完整错误见抽屉内提示）')
  } finally {
    savingTemplate.value = false
  }
}

/** 复制为自定义模板：拉内置模板全量 → POST 新建（code 由后端生成） */
async function copyTemplate(tpl: EmailTemplate) {
  copyingCode.value = tpl.code
  try {
    const detail = await adminApi.getEmailTemplate(tpl.code)
    if (detail.code !== 200 || !detail.data) {
      message.error(shorten(detail.message || '读取模板失败'))
      return
    }
    const d = detail.data
    const res = await adminApi.createEmailTemplate({
      name: `${d.name || d.code} 副本`,
      subject: d.subject || '',
      bodyHtml: d.bodyHtml || '',
      bodyText: d.bodyText || null,
      enabled: d.enabled !== false
    })
    if (res.code === 200 && res.data) {
      message.success(`已复制为自定义模板 ${res.data.code}`)
      await loadTemplates()
      openEdit(res.data)
    } else {
      message.error(shorten(res.message || `接口返回 code=${res.code}`))
    }
  } catch (e) {
    message.error(shorten(isEndpointMissing(e) ? '复制接口未上线（404）' : errorText(e, '复制失败')))
  } finally {
    copyingCode.value = ''
  }
}

/** 删除自定义模板（DELETE 成功为 204；内置/仍被绑定后端返回 400，原样展示） */
function deleteTemplate(tpl: EmailTemplate) {
  Modal.confirm({
    title: '删除自定义模板',
    content: `将永久删除「${tpl.name || tpl.code}」（${tpl.code}）。如果它正被某个用途使用，后端会拒绝删除并返回原因。`,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await adminApi.deleteEmailTemplate(tpl.code)
        message.success(`已删除 ${tpl.code}`)
        if (drawerOpen.value && editing.value?.code === tpl.code) drawerOpen.value = false
        await Promise.all([loadTemplates(), loadPurposes()])
      } catch (e) {
        message.error(shorten(isEndpointMissing(e) ? '删除接口未上线（404）' : errorText(e, '删除失败')))
      }
    }
  })
}

function resetTemplate(tpl: EmailTemplate) {
  Modal.confirm({
    title: '重置为默认模板',
    content: `将把「${tpl.name || tpl.code}」的主题与正文恢复为内置默认内容，当前修改会丢失。`,
    okText: '确认重置',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.resetEmailTemplate(tpl.code)
        if (res.code === 200) {
          message.success('已恢复默认模板')
          if (drawerOpen.value && editing.value?.code === tpl.code) {
            await fetchTemplateDetail(tpl.code)
          }
          await loadTemplates()
        } else {
          message.error(shorten(res.message || '重置失败'))
        }
      } catch (e) {
        message.error(shorten(isEndpointMissing(e) ? ENDPOINT_MISSING_TEXT : errorText(e, '重置失败')))
      }
    }
  })
}

onMounted(() => {
  load()
  loadTemplates()
  loadPurposes()
})
</script>

<style scoped>
.mail-page {
  padding-bottom: 24px;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.err-pre {
  margin: 0;
  padding: 0;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}
.status-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 16px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  background: var(--app-card-bg);
}
.status-banner.ok {
  border-color: rgba(59, 178, 115, 0.38);
}
.status-banner.warn {
  border-color: rgba(229, 113, 83, 0.42);
  background: var(--brand-bg);
}
.status-banner.err {
  border-color: rgba(217, 72, 72, 0.45);
}
.status-main {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-width: 0;
}
.status-dot {
  width: 10px;
  height: 10px;
  margin-top: 6px;
  flex: none;
  border-radius: 50%;
  background: #9aa0a8;
}
.status-banner.ok .status-dot {
  background: #3bb273;
}
.status-banner.warn .status-dot {
  background: #e57153;
}
.status-banner.err .status-dot {
  background: #d94848;
}
.status-title {
  font-weight: 600;
  font-size: 14px;
}
.status-sub {
  margin-top: 2px;
  max-width: 1100px;
  color: var(--app-text-secondary);
  font-size: 12.5px;
}
.status-side {
  flex: none;
}
.mail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1280px) {
  .mail-grid {
    grid-template-columns: 1fr;
  }
}
.form-card {
  padding: 20px 22px;
}
.side-card {
  padding: 18px 20px;
}
.side-card .side-text {
  margin: 4px 0 10px;
  line-height: 1.6;
}
.kv {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  font-size: 13px;
}
.kv > span {
  color: var(--app-text-secondary);
}
.kv b {
  font-weight: 500;
}
.break {
  word-break: break-all;
  text-align: right;
}
.tip-list {
  margin: 6px 0 0;
  padding-left: 18px;
  color: var(--app-text-secondary);
  font-size: 12.5px;
  line-height: 1.9;
}
.tight {
  margin: 14px 0 6px;
}
.form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 4px;
}
.hint {
  margin-top: 4px;
  color: var(--app-text-secondary);
  font-size: 12px;
}
.grow {
  flex: 1;
}
.var-tag {
  margin-bottom: 4px;
}
.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 640px;
  gap: 18px;
  align-items: start;
}
@media (max-width: 1200px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }
}
.field {
  margin-bottom: 14px;
}
.field.inline {
  display: flex;
  align-items: center;
  gap: 10px;
}
.field-label {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
}
.var-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.var-chip {
  cursor: pointer;
  user-select: none;
  transition: all 0.15s;
}
.var-chip:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}
.text-collapse {
  margin-bottom: 4px;
}
.preview-col {
  position: sticky;
  top: 0;
}
.preview-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}
.preview-title {
  font-weight: 600;
  font-size: 13px;
}
.preview-missing {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}
.missing-chip {
  padding: 1px 7px;
  border-radius: 5px;
  background: rgba(229, 113, 83, 0.1);
  color: var(--brand);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}
.preview-wrap {
  display: flex;
  justify-content: center;
  padding: 12px;
  border: 1px dashed var(--app-border);
  border-radius: 10px;
  background: rgba(128, 128, 128, 0.05);
  overflow: auto;
  max-height: 620px;
}
.preview-frame {
  width: 600px;
  height: 560px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: #fff;
  pointer-events: none;
  flex: none;
}
.tiny {
  font-size: 11.5px;
  margin-top: 8px;
  line-height: 1.6;
}
.drawer-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* ── 用途绑定卡片（task-76）── */
.purpose-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.purpose-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-card-bg);
}
.purpose-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.purpose-label {
  font-weight: 600;
  font-size: 13.5px;
}
.purpose-effective {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.purpose-vars {
  line-height: 1.5;
}
.purpose-actions {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.unbind-hint {
  font-size: 12px;
  color: var(--app-text-secondary);
  cursor: not-allowed;
  opacity: 0.75;
}
.unbound-sub {
  color: var(--app-text-secondary);
  opacity: 0.8;
}
.purpose-tag {
  margin: 2px;
}
</style>
