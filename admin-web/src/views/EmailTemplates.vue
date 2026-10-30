<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  UndoOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import CodeEditor from '../components/CodeEditor.vue'
import type { EmailPreview, EmailTemplate, EmailTemplateSummary, MailPurpose } from '../types'
import { copyText, formatDateTime } from '../utils/format'
import { errorText, isEndpointMissing } from '../utils/errors'
import {
  extractTemplateVars,
  groupTemplates,
  purposeText,
  sampleVariableValue,
  templatePurposeText,
  templateVarText,
  unknownTemplateVars,
  validateTemplateVariables
} from '../utils/mail'

const loading = ref(false)
const loadError = ref('')
const endpointMissing = ref(false)
const templates = ref<EmailTemplateSummary[]>([])
const details = ref<Record<string, EmailTemplate>>({})
const purposes = ref<MailPurpose[]>([])
const purposesLoading = ref(false)
const bindingCode = ref('')

// ── 抽屉 ──
const drawerOpen = ref(false)
const createMode = ref(false)
const editing = ref<EmailTemplate | null>(null)
const editingLoading = ref(false)
const saving = ref(false)
const saveError = ref('')
const deleting = ref(false)

const form = reactive({
  name: '',
  subject: '',
  bodyHtml: '',
  bodyText: '',
  enabled: true
})

const bodyEditorRef = ref<any>(null)
const textEditorRef = ref<any>(null)
const subjectInputRef = ref<any>(null)
const lastField = ref<'subject' | 'html' | 'text'>('html')

// ── 预览 ──
const previewHtml = ref('')
const previewText = ref('')
const previewSubject = ref('')
const previewMissing = ref<string[]>([])
const previewWarnings = ref<string[]>([])
const previewError = ref('')
const previewLoading = ref(false)
const previewVars = reactive<Record<string, string>>({})
let previewTimer: ReturnType<typeof setTimeout> | null = null

const groups = computed(() => groupTemplates(templates.value))

/** 编辑时用该模板变量；新建时用全部已知变量（新建保存后由后端解析变量） */
const activeVars = computed<string[]>(() => {
  if (editing.value) return editing.value.variables ?? []
  return knownVars.value
})

/** 所有已加载模板的变量并集（新建模板时的变量白名单） */
const knownVars = computed<string[]>(() => {
  const set = new Set<string>()
  for (const d of Object.values(details.value)) for (const v of d.variables ?? []) set.add(v)
  for (const p of purposes.value) for (const v of p.requiredVariables ?? []) set.add(v)
  return Array.from(set).sort()
})

const drawerTitle = computed(() =>
  createMode.value ? '新建自定义模板' : `编辑模板 · ${editing.value?.code ?? ''}`
)

const subjectCaret = ref<number | null>(null)

function variableCount(code: string): string {
  const d = details.value[code]
  if (!d || !Array.isArray(d.variables)) return '…'
  return String(d.variables.length)
}

function purposeLabelOf(p: MailPurpose) {
  return purposeText(p)
}

async function load() {
  loading.value = true
  loadError.value = ''
  endpointMissing.value = false
  try {
    const res = await adminApi.listEmailTemplates()
    if (res.code === 200) {
      templates.value = res.data ?? []
      const all = await Promise.all(
        templates.value.map(async (t) => {
          try {
            const detail = await adminApi.getEmailTemplate(t.code)
            return detail.code === 200 && detail.data ? ([t.code, detail.data] as [string, EmailTemplate]) : null
          } catch {
            return null
          }
        })
      )
      const map: Record<string, EmailTemplate> = {}
      for (const item of all) if (item) map[item[0]] = item[1]
      details.value = map
    } else {
      loadError.value = res.message || `加载失败（code=${res.code}）`
    }
  } catch (e) {
    if (isEndpointMissing(e)) endpointMissing.value = true
    else loadError.value = errorText(e, '加载邮件模板失败')
  } finally {
    loading.value = false
  }
}

async function loadPurposes() {
  purposesLoading.value = true
  try {
    const res = await adminApi.listMailPurposes()
    if (res.code === 200) purposes.value = res.data ?? []
  } catch (e) {
    if (!isEndpointMissing(e)) message.error(errorText(e, '加载用途绑定失败'))
  } finally {
    purposesLoading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.subject = ''
  form.bodyHtml = ''
  form.bodyText = ''
  form.enabled = true
  saveError.value = ''
  previewHtml.value = ''
  previewText.value = ''
  previewSubject.value = ''
  previewMissing.value = []
  previewWarnings.value = []
  previewError.value = ''
  for (const key of Object.keys(previewVars)) delete previewVars[key]
  if (previewTimer) {
    clearTimeout(previewTimer)
    previewTimer = null
  }
}

async function openCreate() {
  resetForm()
  createMode.value = true
  editing.value = null
  drawerOpen.value = true
  // 新建没有 code，无法调后端预览：给一组变量示例值备用
  for (const v of knownVars.value) previewVars[v] = sampleVariableValue(v)
}

async function openEdit(code: string) {
  resetForm()
  createMode.value = false
  drawerOpen.value = true
  editingLoading.value = true
  try {
    const res = await adminApi.getEmailTemplate(code)
    if (res.code !== 200 || !res.data) {
      saveError.value = res.message || '加载模板详情失败'
      return
    }
    const d = res.data
    editing.value = d
    details.value = { ...details.value, [code]: d }
    form.name = d.name
    form.subject = d.subject
    form.bodyHtml = d.bodyHtml
    form.bodyText = d.bodyText ?? ''
    form.enabled = d.enabled
    for (const v of d.variables ?? []) previewVars[v] = sampleVariableValue(v)
    await runPreview()
  } catch (e) {
    saveError.value = errorText(e, '加载模板详情失败')
  } finally {
    editingLoading.value = false
  }
}

function insertVar(name: string) {
  const text = templateVarText(name)
  if (lastField.value === 'subject') {
    const el = subjectInputRef.value?.input
    const start = el?.selectionStart ?? subjectCaret.value ?? form.subject.length
    form.subject = form.subject.slice(0, start) + text + form.subject.slice(start)
    return
  }
  if (lastField.value === 'text') {
    textEditorRef.value?.insertAtCursor(text)
    return
  }
  bodyEditorRef.value?.insertAtCursor(text)
}

function onSubjectFocus() {
  lastField.value = 'subject'
}

function rememberSubjectCaret() {
  subjectCaret.value = subjectInputRef.value?.input?.selectionStart ?? null
}

async function runPreview() {
  if (previewTimer) {
    clearTimeout(previewTimer)
    previewTimer = null
  }
  previewError.value = ''
  if (createMode.value || !editing.value) {
    previewHtml.value = ''
    return
  }
  previewLoading.value = true
  try {
    const res = await adminApi.previewEmail(editing.value.code, { ...previewVars })
    if (res.code !== 200 || !res.data) {
      previewError.value = res.message || `预览失败（code=${res.code}）`
      return
    }
    const data: EmailPreview = res.data
    previewHtml.value = data.html
    previewText.value = data.text
    previewSubject.value = data.subject ?? ''
    previewMissing.value = data.missingVariables ?? []
    previewWarnings.value = data.warnings ?? []
  } catch (e) {
    previewError.value = errorText(e, '预览失败')
  } finally {
    previewLoading.value = false
  }
}

function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(() => {
    void runPreview()
  }, 500)
}

function checkVariables(): string[] {
  if (createMode.value) {
    // 新建：后端会从正文自动解析变量，仅对明显笔误做提示（用已知变量并集兜底）
    if (!knownVars.value.length) return []
    return unknownTemplateVars(
      extractTemplateVars(form.subject, form.bodyHtml, form.bodyText),
      knownVars.value
    )
  }
  const res = validateTemplateVariables(
    [form.subject, form.bodyHtml, form.bodyText],
    editing.value?.variables ?? []
  )
  return res.ok ? [] : res.unknown
}

async function save() {
  saveError.value = ''
  if (!form.name.trim()) {
    saveError.value = '模板名称不能为空'
    return
  }
  if (!form.subject.trim()) {
    saveError.value = '主题不能为空'
    return
  }
  if (!form.bodyHtml.trim()) {
    saveError.value = 'HTML 正文不能为空'
    return
  }
  const unknown = checkVariables()
  if (unknown.length) {
    saveError.value = `以下变量不在该模板的变量列表里（可能是拼写错误）：${unknown
      .map((v) => templateVarText(v))
      .join('、')}。可用变量请从左侧 chips 点选插入。`
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      subject: form.subject,
      bodyHtml: form.bodyHtml,
      bodyText: form.bodyText || null,
      enabled: form.enabled
    }
    const res = createMode.value
      ? await adminApi.createEmailTemplate(body)
      : await adminApi.updateEmailTemplate(editing.value!.code, body)
    if (res.code !== 200 || !res.data) {
      saveError.value = res.message || `保存失败（code=${res.code}）`
      return
    }
    message.success(createMode.value ? '已新建模板' : '模板已保存（立即生效）')
    await load()
    createMode.value = false
    editing.value = res.data
    form.name = res.data.name
    form.subject = res.data.subject
    form.bodyHtml = res.data.bodyHtml
    form.bodyText = res.data.bodyText ?? ''
    form.enabled = res.data.enabled
    for (const v of res.data.variables ?? []) if (!(v in previewVars)) previewVars[v] = sampleVariableValue(v)
    await runPreview()
  } catch (e) {
    saveError.value = errorText(e, '保存模板失败')
  } finally {
    saving.value = false
  }
}

function resetToDefault(record?: EmailTemplateSummary | EmailTemplate) {
  const code = record?.code ?? editing.value?.code
  const name = record?.name ?? editing.value?.name ?? code
  if (!code) return
  Modal.confirm({
    title: '恢复内置默认模板？',
    content: `将把「${name}」的标题与正文恢复为内置默认内容；当前修改会被覆盖。`,
    okText: '恢复默认',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.resetEmailTemplate(code)
        if (res.code === 200) {
          message.success('已恢复默认模板')
          await load()
          if (editing.value?.code === code) await openEdit(code)
        } else {
          message.error(res.message || `恢复失败（code=${res.code}）`)
        }
      } catch (e) {
        message.error(errorText(e, '恢复默认模板失败'))
      }
    }
  })
}

function deleteTemplate(record?: EmailTemplateSummary | EmailTemplate) {
  const code = record?.code ?? editing.value?.code
  const name = record?.name ?? editing.value?.name ?? code
  if (!code) return
  Modal.confirm({
    title: '删除自定义模板？',
    content: `删除「${name}」（${code}）后不可恢复；已绑定该模板的用途会自动回落到内置模板。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      deleting.value = true
      try {
        const res = await adminApi.deleteEmailTemplate(code)
        if (res.code === 200 || res.code === 204) {
          message.success('已删除模板')
          if (editing.value?.code === code) drawerOpen.value = false
          await load()
          await loadPurposes()
        } else {
          message.error(res.message || `删除失败（code=${res.code}）`)
        }
      } catch (e) {
        message.error(errorText(e, '删除模板失败'))
      } finally {
        deleting.value = false
      }
    }
  })
}

function bindPurpose(p: MailPurpose, code: string) {
  bindingCode.value = p.purpose
  void (async () => {
    try {
      const res = await adminApi.bindMailPurpose(p.purpose, code)
      if (res.code === 200) {
        message.success(`已将「${p.label}」绑定到 ${code}`)
        await loadPurposes()
        await load()
      } else {
        message.error(res.message || `绑定失败（code=${res.code}）`)
      }
    } catch (e) {
      message.error(errorText(e, '绑定用途失败'))
    } finally {
      bindingCode.value = ''
    }
  })()
}

function unbindPurpose(p: MailPurpose) {
  bindingCode.value = p.purpose
  void (async () => {
    try {
      const res = await adminApi.bindMailPurpose(p.purpose, null)
      if (res.code === 200) {
        message.success(`已解除「${p.label}」的自定义绑定，恢复内置模板`)
        await loadPurposes()
        await load()
      } else {
        message.error(res.message || `解绑失败（code=${res.code}）`)
      }
    } catch (e) {
      message.error(errorText(e, '解绑失败'))
    } finally {
      bindingCode.value = ''
    }
  })()
}

function purposeMenu(p: MailPurpose, code: string) {
  bindPurpose(p, code)
}

function copyCode(code: string) {
  void copyText(code)
  message.success('模板 code 已复制')
}

const baseColumns = [
  { title: 'Code', dataIndex: 'code', key: 'code', width: 170, ellipsis: true },
  { title: '名称', dataIndex: 'name', key: 'name', width: 190, ellipsis: true },
  { title: '用途', key: 'purpose', width: 210 },
  { title: '变量数', key: 'vars', width: 80 },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 155 },
  { title: '操作', key: 'action', width: 235 }
]

const purposeColumns = [
  { title: '用途', key: 'purpose', width: 200 },
  { title: '内置模板', dataIndex: 'builtinTemplateCode', key: 'builtin', width: 200 },
  { title: '当前生效', key: 'effective', width: 200 },
  { title: '操作', key: 'action', width: 130 }
]

onMounted(async () => {
  await load()
  await loadPurposes()
})
</script>

<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>邮件模板</h2>
        <div class="sub">
          内置 6 个系统模板 + 自定义模板 · 修改后立即生效 · 内置模板可恢复默认、不可删除
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新建模板
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="endpointMissing"
      type="warning"
      show-icon
      class="mb16"
      message="邮件模板接口未上线（404）"
      description="GET /api/admin/mail/templates 返回 404，通常是后端未部署到该版本。接口可用后点「刷新」生效；本页不做假数据。"
    />
    <a-alert v-else-if="loadError" type="error" show-icon class="mb16" :message="`加载邮件模板失败：${loadError}`">
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <a-skeleton v-if="loading && !templates.length" active :paragraph="{ rows: 8 }" />

    <template v-else>
      <!-- 用途绑定 -->
      <div class="content-card mb16">
        <div class="section-title">
          <h3>用途绑定</h3>
          <span class="muted small">
            发信时按用途选择模板；未绑定自定义模板时使用内置模板（绑定自定义模板后需确保变量齐全）
          </span>
          <span class="grow" />
          <a-button size="small" :loading="purposesLoading" @click="loadPurposes">刷新用途</a-button>
        </div>
        <a-table
          :columns="purposeColumns"
          :data-source="purposes"
          row-key="purpose"
          :pagination="false"
          size="small"
          :loading="purposesLoading"
          class="purposes-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'purpose'">
              <div class="cell-main">{{ purposeLabelOf(record) }}</div>
              <div class="cell-sub mono">{{ record.purpose }}</div>
            </template>
            <template v-else-if="column.key === 'builtin'">
              <a-button type="link" size="small" class="mono" @click="openEdit(record.builtinTemplateCode)">
                {{ record.builtinTemplateCode }}
              </a-button>
            </template>
            <template v-else-if="column.key === 'effective'">
              <a-tag :color="record.boundTemplateCode ? 'orange' : 'default'" class="mono">
                {{ record.effectiveTemplateCode }}
              </a-tag>
              <a-tag v-if="record.boundTemplateCode" color="orange">自定义覆盖</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-button
                v-if="record.boundTemplateCode"
                size="small"
                :loading="bindingCode === record.purpose"
                @click="unbindPurpose(record)"
              >
                解除绑定
              </a-button>
              <span v-else class="muted small">内置默认</span>
            </template>
          </template>
        </a-table>
      </div>

      <!-- 内置模板 -->
      <div class="content-card mb16">
        <div class="section-title">
          <h3>内置模板</h3>
          <span class="muted small">系统内置，可修改样式与文案；误改后可「恢复默认」；不可删除</span>
        </div>
        <a-table
          :columns="baseColumns"
          :data-source="groups.builtin"
          row-key="code"
          :pagination="false"
          size="middle"
          class="tpl-table builtin-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'code'">
              <span class="mono">{{ record.code }}</span>
            </template>
            <template v-else-if="column.key === 'purpose'">
              {{ templatePurposeText(purposes, record.code) }}
            </template>
            <template v-else-if="column.key === 'vars'">
              {{ variableCount(record.code) }}
            </template>
            <template v-else-if="column.key === 'updatedAt'">
              {{ formatDateTime(record.updatedAt) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="row-actions">
                <a-button type="link" size="small" @click="openEdit(record.code)">
                  <template #icon><EditOutlined /></template>
                  编辑
                </a-button>
                <a-dropdown :trigger="['click']">
                  <a-button type="link" size="small">
                    设置用途
                  </a-button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item v-for="p in purposes" :key="p.purpose" @click="purposeMenu(p, record.code)">
                        {{ p.label }}
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
                <a-button type="link" size="small" @click="resetToDefault(record)">
                  <template #icon><UndoOutlined /></template>
                  恢复默认
                </a-button>
              </div>
            </template>
          </template>
        </a-table>
      </div>

      <!-- 自定义模板 -->
      <div class="content-card mb16">
        <div class="section-title">
          <h3>自定义模板</h3>
          <span class="muted small">可新建、编辑、删除；必须先「设置用途」才会在真实发信中生效</span>
          <span class="grow" />
          <a-button size="small" @click="openCreate">
            <template #icon><PlusOutlined /></template>
            新建
          </a-button>
        </div>
        <a-table
          :columns="baseColumns"
          :data-source="groups.custom"
          row-key="code"
          :pagination="false"
          size="middle"
          class="tpl-table custom-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'code'">
              <span class="mono">{{ record.code }}</span>
            </template>
            <template v-else-if="column.key === 'purpose'">
              {{ templatePurposeText(purposes, record.code) }}
            </template>
            <template v-else-if="column.key === 'vars'">
              {{ variableCount(record.code) }}
            </template>
            <template v-else-if="column.key === 'updatedAt'">
              {{ formatDateTime(record.updatedAt) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="row-actions">
                <a-button type="link" size="small" @click="openEdit(record.code)">
                  <template #icon><EditOutlined /></template>
                  编辑
                </a-button>
                <a-dropdown :trigger="['click']">
                  <a-button type="link" size="small">
                    设置用途
                  </a-button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item v-for="p in purposes" :key="p.purpose" @click="purposeMenu(p, record.code)">
                        {{ p.label }}
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
                <a-button type="link" size="small" danger @click="deleteTemplate(record)">
                  <template #icon><DeleteOutlined /></template>
                  删除
                </a-button>
              </div>
            </template>
          </template>
          <template #emptyText>
            <div class="empty-block">
              <div class="cell-main">还没有自定义模板</div>
              <div class="cell-sub">点击右上角「新建」创建自定义模板，再到「用途绑定」里指定用途</div>
            </div>
          </template>
        </a-table>
      </div>
    </template>

    <!-- 编辑抽屉 -->
    <a-drawer
      v-model:open="drawerOpen"
      :width="920"
      :title="drawerTitle"
      class="tpl-drawer"
      destroy-on-close
    >
      <a-skeleton v-if="editingLoading" active :paragraph="{ rows: 10 }" />
      <template v-else>
        <div class="drawer-head">
          <a-tag :color="editing?.isBuiltin === false ? 'orange' : 'blue'">
            {{ createMode ? '新建（保存后生成 code）' : editing?.isBuiltin ? '内置模板' : '自定义模板' }}
          </a-tag>
          <a-tag v-if="!createMode && editing" :color="editing.enabled ? 'green' : 'default'">
            {{ editing.enabled ? '已启用' : '已停用' }}
          </a-tag>
          <a-tag v-if="editing?.code" class="mono">{{ editing.code }}</a-tag>
          <span v-if="editing?.updatedAt" class="muted small">最近保存 {{ formatDateTime(editing.updatedAt) }}</span>
          <span class="grow" />
          <a-button v-if="editing?.code" size="small" @click="copyCode(editing.code)">
            <template #icon><CopyOutlined /></template>
            复制 code
          </a-button>
        </div>

        <a-alert
          v-if="saveError"
          type="error"
          show-icon
          class="mb12"
          message="操作失败（后端返回）"
        >
          <template #description>
            <pre class="err-pre">{{ saveError }}</pre>
          </template>
        </a-alert>

        <div class="drawer-grid">
          <!-- 左：编辑 -->
          <div class="edit-col">
            <a-form layout="vertical">
              <a-row :gutter="12">
                <a-col :span="10">
                  <a-form-item label="模板名称" required>
                    <a-input v-model:value="form.name" placeholder="例如：验证码邮件" allow-clear />
                  </a-form-item>
                </a-col>
                <a-col :span="14">
                  <a-form-item label="启用">
                    <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
                    <span class="muted small ml8">停用后该模板不会用于发信</span>
                  </a-form-item>
                </a-col>
              </a-row>

              <a-form-item required>
                <template #label>
                  <span v-pre>主题（支持 {{变量}}）</span>
                </template>
                <a-input
                  ref="subjectInputRef"
                  v-model:value="form.subject"
                  :placeholder="'[Bond] 验证码 {{code}}'"
                  @focus="onSubjectFocus"
                  @click="rememberSubjectCaret"
                  @keyup="rememberSubjectCaret"
                />
              </a-form-item>

              <a-form-item label="HTML 正文" required>
                <CodeEditor ref="bodyEditorRef" v-model="form.bodyHtml" height="260px" @focus="lastField = 'html'" />
              </a-form-item>

              <a-collapse ghost class="body-text-collapse">
                <a-collapse-panel key="text" header="纯文本正文（可选，兜底显示）">
                  <CodeEditor ref="textEditorRef" v-model="form.bodyText" height="140px" @focus="lastField = 'text'" />
                </a-collapse-panel>
              </a-collapse>

              <div class="vars-box">
                <div class="vars-head">
                  <span class="vars-title">可用变量</span>
                  <span class="muted small">点击插入到光标处（当前：{{ lastField === 'subject' ? '主题' : lastField === 'text' ? '纯文本' : 'HTML 正文' }}）</span>
                </div>
                <div class="var-chips">
                  <a-tag
                    v-for="v in activeVars"
                    :key="v"
                    class="var-chip"
                    @click="insertVar(v)"
                  >
                    {{ v }}
                  </a-tag>
                  <span v-if="!activeVars.length" class="muted small">暂无变量</span>
                </div>
                <div v-if="createMode" class="muted small mt8">
                  新建模板的变量由后端按正文自动解析；保存后若需在预览中使用，请先在预览区填写示例值并点「重新渲染」。
                </div>
                <div v-else class="muted small mt8" v-pre>
                  保存前会校验：正文/主题里出现的 {{变量}} 必须在当前模板的变量列表内（防止拼写错误）。
                </div>
              </div>
            </a-form>
          </div>

          <!-- 右：真实预览 -->
          <div class="preview-col">
            <div class="section-title">
              <h3>真实预览</h3>
              <span class="muted small">POST /api/admin/mail/preview（后端渲染）</span>
              <span class="grow" />
              <a-button size="small" :loading="previewLoading" :disabled="createMode || !editing" @click="runPreview">
                <template #icon><EyeOutlined /></template>
                重新渲染
              </a-button>
            </div>

            <div v-if="createMode" class="preview-empty">
              <div class="cell-main">新建模板需要先保存</div>
              <div class="cell-sub">保存后会自动切到编辑态，用真实接口渲染预览</div>
            </div>
            <template v-else>
              <div class="var-form">
                <div class="var-form-title">变量示例值（可编辑，默认已填示例）</div>
                <a-row :gutter="8">
                  <a-col v-for="v in activeVars" :key="v" :xs="24" :md="12">
                    <div class="var-row">
                      <span class="mono var-name">{{ v }}</span>
                      <a-input
                        v-model:value="previewVars[v]"
                        size="small"
                        @blur="schedulePreview"
                        @press-enter="runPreview"
                      />
                    </div>
                  </a-col>
                </a-row>
              </div>

              <a-alert
                v-if="previewError"
                type="error"
                show-icon
                class="mb12"
                message="预览失败（后端返回）"
              >
                <template #description>
                  <pre class="err-pre">{{ previewError }}</pre>
                </template>
              </a-alert>
              <a-alert
                v-if="previewMissing.length"
                type="warning"
                show-icon
                class="mb12"
                message="以下变量在本次预览中未提供，真实发信时由系统注入："
              >
                <template #description>
                  <span class="mono">{{ previewMissing.join('、') }}</span>
                </template>
              </a-alert>
              <a-alert
                v-if="previewWarnings.length"
                type="info"
                show-icon
                class="mb12"
                message="渲染提示"
              >
                <template #description>
                  <div v-for="(w, i) in previewWarnings" :key="i">{{ w }}</div>
                </template>
              </a-alert>

              <div class="preview-subject mono">{{ previewSubject || '（无主题）' }}</div>
              <a-skeleton v-if="previewLoading && !previewHtml" active :paragraph="{ rows: 8 }" />
              <iframe
                v-else
                class="preview-frame"
                :srcdoc="previewHtml || '<p style=\'color:#999\'>暂无预览内容</p>'"
                sandbox=""
                title="邮件模板预览"
              />
              <a-collapse v-if="previewText" ghost class="mt8">
                <a-collapse-panel key="text" header="纯文本预览（后端渲染）">
                  <pre class="text-preview">{{ previewText }}</pre>
                </a-collapse-panel>
              </a-collapse>
            </template>
          </div>
        </div>
      </template>

      <template #footer>
        <div class="drawer-footer">
          <a-button v-if="!createMode && editing?.isBuiltin" danger @click="resetToDefault()">
            <template #icon><UndoOutlined /></template>
            恢复默认
          </a-button>
          <a-button v-if="!createMode && editing && !editing.isBuiltin" danger :loading="deleting" @click="deleteTemplate()">
            <template #icon><DeleteOutlined /></template>
            删除模板
          </a-button>
          <span class="grow" />
          <a-button @click="drawerOpen = false">关闭</a-button>
          <a-button type="primary" :loading="saving" @click="save">
            {{ createMode ? '创建模板' : '保存' }}
          </a-button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.mt8 {
  margin-top: 8px;
}
.ml8 {
  margin-left: 8px;
}
.grow {
  flex: 1;
}
.head-actions {
  display: flex;
  gap: 10px;
}
.sub {
  color: var(--app-text-secondary);
  font-size: 13px;
  margin-top: 4px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.section-title h3 {
  margin: 0;
  font-size: 15px;
}
.row-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
}
.row-actions :deep(.ant-btn) {
  padding: 0 4px;
}
.tpl-table :deep(.ant-table-cell) {
  vertical-align: top;
}
.cell-main {
  font-size: 13px;
}
.cell-sub {
  color: var(--app-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty-block {
  padding: 18px 0;
}
.drawer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.drawer-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 18px;
}
.edit-col,
.preview-col {
  min-width: 0;
}
.preview-col {
  border-left: 1px solid var(--app-border);
  padding-left: 18px;
}
.vars-box {
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-top: 4px;
}
.vars-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.vars-title {
  font-weight: 600;
  font-size: 13px;
}
.var-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.var-chip {
  cursor: pointer;
  user-select: none;
}
.var-chip:hover {
  color: var(--brand);
  border-color: var(--brand);
}
.body-text-collapse {
  margin-bottom: 6px;
}
.var-form {
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.var-form-title {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-bottom: 6px;
}
.var-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.var-name {
  width: 110px;
  flex: none;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-subject {
  padding: 8px 10px;
  border: 1px solid var(--app-border);
  border-bottom: none;
  border-radius: 6px 6px 0 0;
  background: var(--app-bg);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-frame {
  width: 100%;
  height: 380px;
  border: 1px solid var(--app-border);
  border-radius: 0 0 6px 6px;
  background: #fff;
}
.preview-empty {
  border: 1px dashed var(--app-border);
  border-radius: 8px;
  padding: 28px;
  text-align: center;
}
.text-preview {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  max-height: 220px;
  overflow: auto;
  background: var(--app-bg);
  padding: 8px 10px;
  border-radius: 6px;
}
.err-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
}
.drawer-footer {
  display: flex;
  align-items: center;
  width: 100%;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: var(--app-text-secondary);
}
.small {
  font-size: 12px;
}
</style>
