<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  CopyOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  MailOutlined,
  ReloadOutlined,
  SearchOutlined,
  UndoOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import type { SystemConfigItem } from '../types'
import { copyText, formatDateTime, formatOperatorId } from '../utils/format'
import { errorText } from '../utils/errors'
import {
  BYTE_UNITS,
  byteConfigDisplay,
  bytesFromParts,
  bytesToParts,
  configControl,
  configControlHint,
  configTypeLabel,
  configValuePreview,
  formatJsonText,
  isByteConfig,
  isLongConfigValue,
  normalizeConfigValue,
  normalizeRawByteCount,
  validateConfigValue
} from '../utils/settings'

const router = useRouter()

const configs = ref<SystemConfigItem[]>([])
const loading = ref(false)
const loadError = ref('')

const keyword = ref('')
const typeFilter = ref<string>('all')

const editOpen = ref(false)
const editing = ref<SystemConfigItem | null>(null)
const editingType = ref('string')
const editingControl = ref<'boolean' | 'integer' | 'number' | 'json' | 'text'>('text')
const editText = ref('')
const editNumber = ref<number | null>(null)
const editBool = ref(false)
const editError = ref('')
const saveError = ref('')
const saving = ref(false)

// 字节类配置（task-108）：数值 + 单位编辑器 / 原始字节切换
const byteMode = ref(false)
const byteRawMode = ref(false)
const byteAmount = ref<number | null>(0)
const byteUnit = ref<(typeof BYTE_UNITS)[number]>('B')
const byteRawText = ref('')

const byteUnitOptions = BYTE_UNITS.map((u) => ({ label: u, value: u }))
const bytePreview = computed(() => {
  if (byteRawMode.value) {
    const raw = normalizeRawByteCount(byteRawText.value)
    return raw === null ? '' : `${raw} 字节`
  }
  const bytes = bytesFromParts(byteAmount.value, byteUnit.value)
  return bytes === null ? '' : `${bytes} 字节`
})
// 两种编辑模式互相回填，避免切换后看到过期数字
watch(byteRawMode, (raw) => {
  if (!byteMode.value) return
  if (raw) {
    const bytes = bytesFromParts(byteAmount.value, byteUnit.value)
    if (bytes !== null) byteRawText.value = bytes
  } else {
    const parsed = normalizeRawByteCount(byteRawText.value)
    if (parsed !== null) {
      const parts = bytesToParts(Number(parsed))
      byteAmount.value = parts.value
      byteUnit.value = parts.unit
    }
  }
})

const resetOpen = ref(false)
const resetting = ref(false)
const resetError = ref('')

const typeOptions = computed(() => {
  const set = new Set<string>()
  for (const c of configs.value) set.add(configTypeLabel(c.configType))
  return Array.from(set).sort().map((t) => ({ label: t, value: t }))
})

const filteredConfigs = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return configs.value.filter((c) => {
    if (typeFilter.value !== 'all' && configTypeLabel(c.configType) !== typeFilter.value) return false
    if (!kw) return true
    return (
      String(c.configKey).toLowerCase().includes(kw) ||
      String(c.description ?? '').toLowerCase().includes(kw) ||
      String(c.configValue ?? '').toLowerCase().includes(kw)
    )
  })
})

function isBoolConfig(row: SystemConfigItem) {
  return configControl(row.configType) === 'boolean'
}

function isLongValue(row: SystemConfigItem) {
  return isLongConfigValue(row.configValue)
}

function valuePreview(row: SystemConfigItem) {
  return configValuePreview(row.configValue)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listConfig()
    if (res.code === 200) {
      configs.value = res.data ?? []
    } else {
      loadError.value = res.message || `加载失败（code=${res.code}）`
    }
  } catch (e) {
    loadError.value = errorText(e, '加载系统配置失败')
  } finally {
    loading.value = false
  }
}

async function copyKey(key: string) {
  await copyText(key)
  message.success('配置键已复制')
}

function openEdit(row: SystemConfigItem) {
  editing.value = row
  editingType.value = configTypeLabel(row.configType)
  editingControl.value = configControl(row.configType)
  editError.value = ''
  saveError.value = ''

  const raw = String(row.configValue ?? '')
  byteMode.value = isByteConfig(row)
  byteRawMode.value = false
  byteRawText.value = raw
  if (byteMode.value) {
    const parts = bytesToParts(Number(raw))
    byteAmount.value = parts.value
    byteUnit.value = parts.unit
  }
  if (editingControl.value === 'boolean') {
    editBool.value = raw.trim().toLowerCase() === 'true'
  } else if (editingControl.value === 'integer' || editingControl.value === 'number') {
    const n = Number(raw)
    editNumber.value = raw.trim() !== '' && Number.isFinite(n) ? n : null
  } else if (editingControl.value === 'json') {
    const formatted = formatJsonText(raw)
    editText.value = formatted.ok ? formatted.text : raw
  } else {
    editText.value = raw
  }
  editOpen.value = true
}

/** 当前控件对应的字符串值（提交给后端前统一转 string） */
function currentInputValue(): string {
  if (editingControl.value === 'boolean') return editBool.value ? 'true' : 'false'
  if (editingControl.value === 'integer' || editingControl.value === 'number') {
    return editNumber.value === null ? '' : String(editNumber.value)
  }
  return editText.value
}

function formatEditJson() {
  const formatted = formatJsonText(editText.value)
  if (formatted.ok) {
    editText.value = formatted.text
    editError.value = ''
    message.success('已格式化 JSON')
  } else {
    editError.value = `JSON 格式不正确：${formatted.error ?? ''}`
  }
}

async function saveEdit() {
  const row = editing.value
  if (!row) return
  let submitValue = ''
  if (byteMode.value) {
    if (byteRawMode.value) {
      const normalized = normalizeRawByteCount(byteRawText.value)
      if (normalized === null) {
        editError.value = '原始字节必须是整数，且不能超出前端安全整数范围（数值过大）'
        return
      }
      submitValue = normalized
    } else {
      if (byteAmount.value === null || !Number.isInteger(byteAmount.value) || byteAmount.value < 0) {
        editError.value = '字节必须是整数'
        return
      }
      const bytes = bytesFromParts(byteAmount.value, byteUnit.value)
      if (bytes === null) {
        editError.value = '数值过大（换算后超出前端安全整数范围）'
        return
      }
      submitValue = bytes
    }
    editError.value = ''
  } else {
    const raw = currentInputValue()
    const invalid = validateConfigValue(editingType.value, raw)
    if (invalid) {
      editError.value = invalid
      return
    }
    submitValue = normalizeConfigValue(editingType.value, raw)
  }
  editError.value = ''
  saveError.value = ''
  saving.value = true
  try {
    const res = await adminApi.updateConfig(row.configKey, submitValue)
    if (res.code === 200) {
      editOpen.value = false
      message.success(`已保存：${row.configKey}（立即生效）`)
      await load()
    } else {
      saveError.value = res.message || `保存失败（code=${res.code}）`
    }
  } catch (e) {
    saveError.value = errorText(e, '保存失败')
  } finally {
    saving.value = false
  }
}

function openReset() {
  resetError.value = ''
  resetOpen.value = true
}

async function doReset() {
  resetting.value = true
  resetError.value = ''
  try {
    const res = await adminApi.resetConfig()
    if (res.code === 200) {
      resetOpen.value = false
      message.success('已恢复默认配置（system_configs 已重建）')
      await load()
    } else {
      resetError.value = res.message || `重置失败（code=${res.code}）`
    }
  } catch (e) {
    resetError.value = errorText(e, '重置失败')
  } finally {
    resetting.value = false
  }
}

const columns = [
  { title: '说明', dataIndex: 'description', key: 'description', width: 210, ellipsis: true },
  { title: '配置键', dataIndex: 'configKey', key: 'configKey', width: 250, ellipsis: true },
  { title: '类型', dataIndex: 'configType', key: 'configType', width: 96 },
  { title: '最近修改', dataIndex: 'updatedAt', key: 'updatedAt', width: 160 },
  { title: '当前值', dataIndex: 'configValue', key: 'configValue', width: 270 },
  { title: '操作', key: 'action', width: 96, align: 'center' as const }
]

onMounted(load)
</script>

<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>系统设置</h2>
        <div class="sub">
          system_configs 表 · 共 {{ configs.length }} 项 · 保存后立即生效（网关缓存约 60s 内失效）
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button danger :disabled="loading || !configs.length" @click="openReset">
          <template #icon><UndoOutlined /></template>
          重置为默认
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      :message="`加载系统配置失败：${loadError}`"
    >
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="content-card mail-entry mb16" @click="router.push('/email')">
      <div class="mail-entry-icon"><MailOutlined /></div>
      <div class="mail-entry-body">
        <h3>邮件服务</h3>
        <p>SMTP / Resend / bondmail 发信配置与自检、测试发信，在「邮件服务」页维护。</p>
      </div>
      <a-button type="link">前往设置 →</a-button>
    </div>

    <div class="filter-bar">
      <a-input
        v-model:value="keyword"
        placeholder="搜索配置键 / 说明 / 值"
        allow-clear
        style="max-width: 320px"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-select v-model:value="typeFilter" style="width: 140px" :options="[{ label: '全部类型', value: 'all' }, ...typeOptions]" />
      <span class="grow" />
      <span class="muted small">
        显示 {{ filteredConfigs.length }} / {{ configs.length }} 项 · 点击「编辑」在弹窗中修改
      </span>
    </div>

    <div class="content-card table-card">
      <a-skeleton v-if="loading && !configs.length" active :paragraph="{ rows: 8 }" />
      <a-table
        v-else
        :columns="columns"
        :data-source="filteredConfigs"
        row-key="configKey"
        :pagination="false"
        :loading="loading"
        size="middle"
        class="config-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'description'">
            <span class="desc-text" :title="record.description || ''">
              {{ record.description || '—' }}
            </span>
          </template>

          <template v-else-if="column.key === 'configKey'">
            <div class="key-cell">
              <span class="mono key-text">{{ record.configKey }}</span>
              <a-button type="text" size="small" class="copy-btn" aria-label="复制配置键" @click="copyKey(record.configKey)">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </div>
          </template>

          <template v-else-if="column.key === 'configType'">
            <a-tag>{{ configTypeLabel(record.configType) }}</a-tag>
          </template>

          <template v-else-if="column.key === 'updatedAt'">
            <div class="cell-main">{{ formatDateTime(record.updatedAt) }}</div>
            <div class="cell-sub mono">
              <a-tooltip :title="record.updatedBy ? `完整 ID：${record.updatedBy}` : '后端未返回操作人'">
                <span>操作人 {{ formatOperatorId(record.updatedBy) }}</span>
              </a-tooltip>
            </div>
          </template>

          <template v-else-if="column.key === 'configValue'">
            <template v-if="isByteConfig(record)">
              <div class="cell-main">{{ byteConfigDisplay(record.configValue).text }}</div>
              <div class="cell-sub mono">原始值 {{ byteConfigDisplay(record.configValue).rawText }} 字节</div>
            </template>
            <a-tag v-else-if="isBoolConfig(record)" :color="String(record.configValue).toLowerCase() === 'true' ? 'green' : 'default'">
              {{ String(record.configValue).toLowerCase() === 'true' ? '启用' : '关闭' }}
            </a-tag>
            <a-tooltip v-else :title="record.configValue" placement="topLeft" :overlay-style="{ maxWidth: '560px' }">
              <span class="mono value-text" :class="{ 'is-long': isLongValue(record) }">
                {{ valuePreview(record) }}
              </span>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openEdit(record)">
              <template #icon><EditOutlined /></template>
              编辑
            </a-button>
          </template>
        </template>

        <template #emptyText>
          <div class="empty-block">
            <div class="cell-main">没有匹配的配置项</div>
            <div class="cell-sub">调整搜索关键词或类型筛选后重试</div>
          </div>
        </template>
      </a-table>
    </div>

    <!-- 编辑弹窗：按类型给控件 -->
    <a-modal
      v-model:open="editOpen"
      :title="editing ? `编辑配置 · ${editing.configKey}` : '编辑配置'"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      :width="640"
      @ok="saveEdit"
    >
      <div v-if="editing" class="edit-body">
        <div class="edit-meta">
          <div class="meta-line">
            <span class="meta-label">说明</span>
            <span>{{ editing.description || '—' }}</span>
          </div>
          <div class="meta-line">
            <span class="meta-label">类型</span>
            <a-tag>{{ editingType }}</a-tag>
            <span class="meta-hint">{{ configControlHint(editing.configType) }}</span>
          </div>
          <div class="meta-line">
            <span class="meta-label">最近修改</span>
            <span>{{ formatDateTime(editing.updatedAt) }}</span>
            <a-tooltip :title="editing.updatedBy ? `完整 ID：${editing.updatedBy}` : '后端未返回操作人'">
              <span class="meta-hint mono">操作人 {{ formatOperatorId(editing.updatedBy) }}</span>
            </a-tooltip>
          </div>
          <div class="meta-line">
            <span class="meta-label">配置键</span>
            <span class="mono">{{ editing.configKey }}</span>
            <a-button type="link" size="small" @click="copyKey(editing.configKey)">复制</a-button>
          </div>
        </div>

        <a-form layout="vertical" class="edit-form">
          <a-form-item label="当前值" :validate-status="editError ? 'error' : undefined" :help="editError || undefined">
            <template v-if="byteMode">
              <div class="byte-editor">
                <a-input-number
                  v-model:value="byteAmount"
                  :min="0"
                  :disabled="byteRawMode"
                  style="width: 160px"
                  placeholder="数值"
                />
                <a-select
                  v-model:value="byteUnit"
                  :options="byteUnitOptions"
                  :disabled="byteRawMode"
                  style="width: 110px"
                />
                <span class="muted small">= {{ bytePreview || '超出安全范围' }}</span>
              </div>
              <div class="byte-raw-row">
                <a-checkbox v-model:checked="byteRawMode">直接输入原始字节数</a-checkbox>
                <a-input
                  v-if="byteRawMode"
                  v-model:value="byteRawText"
                  class="mono raw-bytes"
                  placeholder="例如 5242880"
                />
              </div>
              <div class="muted small byte-hint">
                按 1024 进制换算；能被整除的自动用更高单位（如 5242880 → 5 MB），不能整除的用原始字节显示，避免被截断。
              </div>
            </template>
            <a-switch
              v-else-if="editingControl === 'boolean'"
              v-model:checked="editBool"
              checked-children="启用"
              un-checked-children="关闭"
            />
            <a-input-number
              v-else-if="editingControl === 'integer'"
              v-model:value="editNumber"
              :precision="0"
              style="width: 100%"
              placeholder="请输入整数"
            />
            <a-input-number
              v-else-if="editingControl === 'number'"
              v-model:value="editNumber"
              style="width: 100%"
              placeholder="请输入小数"
            />
            <div v-else-if="editingControl === 'json'">
              <a-textarea v-model:value="editText" :rows="8" class="mono" placeholder="请输入 JSON" />
              <div class="json-actions">
                <a-button size="small" @click="formatEditJson">格式化 JSON</a-button>
                <span class="muted small">保存时会压缩为一行；后端解析失败会原样返回 400 错误</span>
              </div>
            </div>
            <a-textarea
              v-else
              v-model:value="editText"
              :auto-size="{ minRows: 3, maxRows: 10 }"
              class="mono"
              placeholder="请输入文本"
            />
          </a-form-item>
        </a-form>
      </div>

      <a-alert
        v-if="saveError"
        type="error"
        show-icon
        class="mb12"
        message="保存失败（后端返回）"
      >
        <template #description>
          <pre class="err-pre">{{ saveError }}</pre>
        </template>
      </a-alert>

      <template #footer>
        <div class="modal-footer">
          <a-button danger type="text" :disabled="saving" @click="openReset">
            <template #icon><ExclamationCircleOutlined /></template>
            恢复全部默认值…
          </a-button>
          <span class="grow" />
          <a-button @click="editOpen = false">取消</a-button>
          <a-button type="primary" :loading="saving" @click="saveEdit">保存</a-button>
        </div>
      </template>
    </a-modal>

    <!-- 重置二次确认弹窗 -->
    <a-modal
      v-model:open="resetOpen"
      title="重置为默认值"
      :confirm-loading="resetting"
      ok-text="确认重置"
      cancel-text="取消"
      :ok-button-props="{ danger: true }"
      @ok="doReset"
    >
      <p>
        将调用 <span class="mono">POST /api/admin/config/reset</span>，把
        <span class="mono">system_configs</span> 表恢复为内置默认值。
      </p>
      <p class="danger-text">当前所有自定义修改都会被覆盖，且无法撤销。确认继续？</p>
      <a-alert
        v-if="resetError"
        type="error"
        show-icon
        class="mt12"
        message="重置失败（后端返回）"
      >
        <template #description>
          <pre class="err-pre">{{ resetError }}</pre>
        </template>
      </a-alert>
    </a-modal>
  </div>
</template>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.mt12 {
  margin-top: 12px;
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
.mail-entry {
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.mail-entry:hover {
  border-color: var(--brand);
}
.mail-entry-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #fff;
  background: var(--brand);
  flex: none;
}
.mail-entry-body {
  flex: 1;
}
.mail-entry-body h3 {
  margin: 0;
  font-size: 15px;
}
.mail-entry-body p {
  margin: 4px 0 0;
  color: var(--app-text-secondary);
  font-size: 13px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.table-card {
  padding: 0;
  overflow: hidden;
}
.config-table :deep(.ant-table-cell) {
  vertical-align: top;
}
.key-cell {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.key-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.copy-btn {
  flex: none;
  opacity: 0.55;
}
.copy-btn:hover {
  opacity: 1;
}
.desc-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-text-secondary);
}
.value-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value-text.is-long {
  color: var(--app-text-secondary);
}
.cell-main {
  font-size: 13px;
}
.cell-sub {
  color: var(--app-text-secondary);
  font-size: 12px;
  margin-top: 2px;
}
.empty-block {
  padding: 18px 0;
}
.edit-body {
  max-height: 60vh;
  overflow: auto;
}
.edit-meta {
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 14px;
}
.meta-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin: 5px 0;
}
.meta-label {
  color: var(--app-text-secondary);
  width: 64px;
  flex: none;
}
.meta-hint {
  color: var(--app-text-secondary);
  font-size: 12px;
}
.edit-form {
  margin-top: 4px;
}
.json-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
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
.modal-footer {
  display: flex;
  align-items: center;
  width: 100%;
}
.danger-text {
  color: #cf1322;
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
.byte-editor {
  display: flex;
  align-items: center;
  gap: 10px;
}
.byte-raw-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}
.raw-bytes {
  flex: 1;
  max-width: 320px;
}
.byte-hint {
  margin-top: 6px;
  line-height: 1.6;
}
</style>
