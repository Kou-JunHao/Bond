<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>投递日志</h2>
        <div class="sub">
          邮件投递记录与失败排障 · 共 {{ total }} 条
          <span v-if="placeholdersOnly" class="sub-extra">（跨页扫描最近 {{ scannedCount }} 条，仅显示占位符残留的行）</span>
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="refreshAll">
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
      message="投递日志接口不可用（404）"
      description="GET /api/admin/mail/delivery 返回 404，通常是后端未部署到该版本。接口可用后本页会自动展示真实投递记录。"
    />
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      :message="'投递日志加载失败：' + loadError"
      description="这是后端返回的原始错误，未被改写；可点「重试」重新查询。"
    >
      <template #action>
        <a-button size="small" @click="refreshAll">重试</a-button>
      </template>
    </a-alert>

    <!-- 统计卡 -->
    <div class="stat-grid stats">
      <div class="stat-card">
        <div class="label"><span class="stat-icon"><MailOutlined /></span>近 {{ days }} 天投递总数</div>
        <div class="value">{{ stats ? formatNumber(stats.total) : '—' }}</div>
        <div class="hint">按创建时间统计</div>
      </div>
      <div class="stat-card">
        <div class="label"><span class="stat-icon ok-icon"><CheckCircleOutlined /></span>成功</div>
        <div class="value ok-text">{{ stats ? formatNumber(stats.sent) : '—' }}</div>
        <div class="hint">发送成功（provider 返回可投递）</div>
      </div>
      <div class="stat-card">
        <div class="label"><span class="stat-icon fail-icon"><CloseCircleOutlined /></span>失败</div>
        <div class="value fail-text">{{ stats ? formatNumber(stats.failed) : '—' }}</div>
        <div class="hint">发送失败，可展开看后端原始错误</div>
      </div>
      <div class="stat-card">
        <div class="label"><span class="stat-icon"><PercentageOutlined /></span>失败率</div>
        <div class="value" :class="failureRateValueClass">{{ failureRateText }}</div>
        <div class="hint">失败数 / 总数（百分比保留 2 位）</div>
      </div>
    </div>

    <div class="range-bar">
      <a-radio-group v-model:value="days" button-style="solid" :disabled="statsLoading" @change="onDaysChange">
        <a-radio-button :value="7">近 7 天</a-radio-button>
        <a-radio-button :value="14">近 14 天</a-radio-button>
        <a-radio-button :value="30">近 30 天</a-radio-button>
      </a-radio-group>
      <span class="muted small">时间范围作用于上方统计；列表为后端全量分页（接口未提供时间参数）</span>
    </div>

    <!-- 按用途分布（统计接口 byPurpose，失败部分叠红） -->
    <div class="dist-card">
      <div class="dist-head">
        <span class="cell-main">近 {{ days }} 天按用途分布</span>
        <span class="muted small">按投递量排序 · 悬停看失败数</span>
      </div>
      <div v-if="purposeDist.length" class="dist-list">
        <a-tooltip v-for="item in purposeDist" :key="item.purpose" :title="`${item.label}：共 ${item.total} 封，失败 ${item.failed} 封`">
          <div class="dist-row">
            <span class="dist-label">{{ item.label }}</span>
            <span class="dist-track"><span class="dist-bar" :style="{ width: item.percent + '%' }" /></span>
            <span class="dist-count">{{ item.total }}</span>
          </div>
        </a-tooltip>
      </div>
      <div v-else class="muted dist-empty">近 {{ days }} 天暂无投递分布数据</div>
    </div>

    <div class="filter-bar">
      <a-select
        v-model:value="statusFilter"
        placeholder="全部状态"
        allow-clear
        style="width: 140px"
        :options="statusOptions"
        @change="applyFilters"
      />
      <a-select
        v-model:value="purposeFilter"
        placeholder="全部用途"
        allow-clear
        style="width: 180px"
        :options="purposeOptions"
        @change="applyFilters"
      />
      <label class="ph-switch">
        <a-switch v-model:checked="placeholdersOnly" size="small" @change="applyFilters" />
        <span>只看占位符残留</span>
      </label>
      <a-button type="primary" @click="applyFilters">查询</a-button>
      <a-button @click="resetFilters">重置</a-button>
    </div>

    <div class="content-card table-card">
      <a-table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        row-key="id"
        :pagination="false"
        size="middle"
        :scroll="{ x: 1103 }"
        :row-class-name="rowClassName"
        :expandable="{ rowExpandable: rowExpandable }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            <div class="cell-main nowrap">{{ formatDateTime(record.createdAt, 'YYYY-MM-DD HH:mm:ss') }}</div>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAt) }}</div>
          </template>

          <template v-else-if="column.key === 'toMasked'">
            <span class="mono">{{ record.toMasked || '—' }}</span>
          </template>

          <template v-else-if="column.key === 'purpose'">
            <a-tag class="purpose-tag">{{ purposeLabel(record.purpose) }}</a-tag>
          </template>

          <template v-else-if="column.key === 'templateCode'">
            <a-tooltip :title="record.subject || record.templateCode">
              <span class="mono template-code">{{ record.templateCode }}</span>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'status'">
            <span class="status-pill" :class="record.status === 'sent' ? 'sent' : 'failed'">
              <span class="status-dot" />
              {{ record.status === 'sent' ? '成功' : record.status === 'failed' ? '失败' : record.status }}
            </span>
          </template>

          <template v-else-if="column.key === 'variableKeys'">
            <template v-if="(record.variableKeys || []).length">
              <span class="var-chips">
                <span v-for="key in chipParts(record).shown" :key="key" class="var-chip">{{ key }}</span>
                <a-tooltip v-if="chipParts(record).more > 0" :title="record.variableKeys.join(', ')">
                  <span class="var-chip more">+{{ chipParts(record).more }}</span>
                </a-tooltip>
              </span>
            </template>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'placeholdersLeft'">
            <a-tooltip v-if="(record.placeholdersLeft || 0) > 0" title="有变量未被替换（占位符残留），点击只看这类记录">
              <button class="ph-badge" type="button" @click.stop="onlyPlaceholders">残留 {{ record.placeholdersLeft }}</button>
            </a-tooltip>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'durationMs'">
            <span class="nowrap">{{ record.durationMs ?? '—' }} ms</span>
          </template>

          <template v-else-if="column.key === 'provider'">
            <span class="mono">{{ record.provider || '—' }}</span>
          </template>

          <template v-else-if="column.key === 'id'">
            <span class="mono muted">{{ record.id }}</span>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <div v-if="record.status === 'failed'" class="fail-detail">
            <div class="fail-head">
              <span class="cell-main">失败原因（后端原文）</span>
              <a-button size="small" type="text" @click="copyFailure(record)">
                <template #icon><CopyOutlined /></template>
                复制
              </a-button>
            </div>
            <pre class="fail-pre">{{ record.failureReason || '（后端未返回 failureReason）' }}</pre>
          </div>
          <div v-else class="muted">投递成功，无失败信息。</div>
        </template>

        <template #emptyText>
          <a-empty>
            <template #description>
              <div class="cell-main">{{ emptyText.main }}</div>
              <div class="cell-sub">{{ emptyText.sub }}</div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">
          第 {{ page }} 页 · 本页 {{ items.length }} 条 · 共 {{ total }} 条
        </span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="paginationTotal"
          :show-size-changer="true"
          :page-size-options="['20', '50', '100']"
          @change="onPageChange"
          @showSizeChange="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  CheckCircleOutlined, CloseCircleOutlined, CopyOutlined, MailOutlined, PercentageOutlined, ReloadOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { errorText, isEndpointMissing } from '../utils/errors'
import { copyText, formatDateTime, formatNumber, fromNow } from '../utils/format'
import {
  failureRateClass,
  filterPlaceholderRows,
  formatFailureRate,
  purposeLabelOf,
  rowClassNameOf,
  rowExpandable as rowExpandableByStatus,
  slicePage,
  splitVariableKeys
} from '../utils/mailDelivery'
import type { MailDeliveryLog, MailDeliveryStats, MailPurpose } from '../types'

const loading = ref(false)
const statsLoading = ref(false)
const items = ref<MailDeliveryLog[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const days = ref(7)
const stats = ref<MailDeliveryStats | null>(null)
const statusFilter = ref<string | undefined>(undefined)
const purposeFilter = ref<string | undefined>(undefined)
const placeholdersOnly = ref(false)
const purposeMap = ref<Record<string, string>>({})
const purposeOptions = ref<Array<{ label: string; value: string }>>([])
const endpointMissing = ref(false)
const loadError = ref('')
/** 占位符筛选时跨页扫描的原始行（客户端过滤，接口没有该筛选参数） */
const scannedRows = ref<MailDeliveryLog[] | null>(null)
const scannedCount = ref(0)

const statusOptions = [
  { label: '成功', value: 'sent' },
  { label: '失败', value: 'failed' }
]

const columns = [
  { title: '时间', key: 'createdAt', width: 152 },
  { title: '收件人', key: 'toMasked', width: 160 },
  { title: '用途', key: 'purpose', width: 112 },
  { title: '模板', key: 'templateCode', width: 150 },
  { title: '状态', key: 'status', width: 82 },
  { title: '变量', key: 'variableKeys', width: 180 },
  { title: '占位符残留', key: 'placeholdersLeft', width: 104 },
  { title: '耗时', key: 'durationMs', width: 82 },
  { title: '通道', key: 'provider', width: 72 }
]

const paginationTotal = computed(() => (placeholdersOnly.value ? (scannedRows.value?.length || 0) : total.value))

/** 失败率：接口给的是百分比 0–100；total=0 显示 0.00% */
const failureRateText = computed(() => {
  const s = stats.value
  if (!s) return '—'
  return formatFailureRate(s.total, s.failureRate)
})
const failureRateValueClass = computed(() => (stats.value ? failureRateClass(stats.value.total, stats.value.failed, stats.value.failureRate) : 'ok-text'))

/** 按用途分布：byPurpose 来自统计接口，不前端重算统计口径 */
const purposeDist = computed(() => {
  const s = stats.value
  const rows = s?.byPurpose || []
  if (!rows.length) return []
  const dayTotal = s?.total || rows.reduce((sum, r) => sum + (r.total || 0), 0) || 1
  return [...rows]
    .sort((a, b) => (b.total || 0) - (a.total || 0))
    .map((r) => ({
      purpose: r.purpose,
      label: purposeLabel(r.purpose),
      total: r.total || 0,
      failed: r.failed || 0,
      percent: Math.max(2, Math.round(((r.total || 0) / dayTotal) * 100))
    }))
})

const emptyText = computed(() => {
  if (placeholdersOnly.value) {
    return {
      main: '暂无投递记录',
      sub: `近 ${days.value} 天没有占位符残留的投递记录；关闭上方开关可查看全部投递记录`
    }
  }
  if (statusFilter.value || purposeFilter.value) {
    return {
      main: '没有符合条件的投递记录',
      sub: '调整筛选条件后重试；或发一封测试邮件后这里会立即出现记录'
    }
  }
  return {
    main: '暂无投递记录',
    sub: `近 ${days.value} 天没有投递记录；发一封测试邮件后这里会立即出现记录`
  }
})

function purposeLabel(purpose: string): string {
  return purposeLabelOf(purposeMap.value, purpose)
}

function rowClassName(record: MailDeliveryLog): string {
  return rowClassNameOf(record)
}

function rowExpandable(record: MailDeliveryLog): boolean {
  return rowExpandableByStatus(record)
}

function chipParts(record: MailDeliveryLog): { shown: string[]; more: number } {
  return splitVariableKeys(record.variableKeys)
}

async function copyFailure(record: MailDeliveryLog) {
  const text = record.failureReason || ''
  if (!text) {
    message.warning('该行没有 failureReason')
    return
  }
  const ok = await copyText(text)
  message[ok ? 'success' : 'warning'](ok ? '失败原因已复制' : '复制失败，请手动选择文本')
}

function onlyPlaceholders() {
  placeholdersOnly.value = true
  applyFilters()
}

async function loadStats() {
  statsLoading.value = true
  try {
    // 统计接口偶发超时（网关抖动）：重试 2 次；仍失败则卡片显示 —，不影响列表
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        const res = await adminApi.getMailDeliveryStats(days.value)
        if (res.code === 200) {
          stats.value = res.data || null
          return
        }
      } catch {
        if (attempt === 3) {
          stats.value = null
          return
        }
        await new Promise((resolve) => setTimeout(resolve, 700))
      }
    }
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    if (placeholdersOnly.value) {
      await scanAndFilterPlaceholders()
    } else {
      scannedRows.value = null
      scannedCount.value = 0
      const res = await adminApi.listMailDelivery({
        page: page.value,
        size: pageSize.value,
        status: statusFilter.value || undefined,
        purpose: purposeFilter.value || undefined
      })
      if (res.code !== 200) throw new Error(res.message || `接口返回 code=${res.code}`)
      items.value = res.data?.items || []
      total.value = res.data?.total || 0
      endpointMissing.value = false
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
      items.value = []
      total.value = 0
      loadError.value = ''
    } else {
      loadError.value = errorText(err, '投递日志加载失败')
      message.error(loadError.value)
    }
  } finally {
    loading.value = false
  }
}

/** 接口暂不支持 placeholdersLeft>0 筛选：跨页扫描（最多 10 页 / 1000 条）后客户端过滤 */
async function scanAndFilterPlaceholders() {
  const all: MailDeliveryLog[] = []
  let serverTotal = 0
  for (let p = 1; p <= 10; p += 1) {
    const res = await adminApi.listMailDelivery({
      page: p,
      size: 100,
      status: statusFilter.value || undefined,
      purpose: purposeFilter.value || undefined
    })
    if (res.code !== 200) throw new Error(res.message || `接口返回 code=${res.code}`)
    const rows = res.data?.items || []
    serverTotal = res.data?.total || 0
    all.push(...rows)
    if (rows.length < 100) break
    if (serverTotal > 0 && all.length >= serverTotal) break
  }
  const filtered = filterPlaceholderRows(all)
  scannedRows.value = filtered
  scannedCount.value = all.length
  total.value = filtered.length
  page.value = 1
  items.value = slicePage(filtered, 1, pageSize.value)
  endpointMissing.value = false
}

async function loadPurposes() {
  try {
    const res = await adminApi.listMailPurposes()
    if (res.code === 200 && Array.isArray(res.data)) {
      const map: Record<string, string> = {}
      const options: Array<{ label: string; value: string }> = []
      for (const p of res.data as MailPurpose[]) {
        if (!p?.purpose) continue
        map[p.purpose] = p.label || p.purpose
        options.push({ label: p.label || p.purpose, value: p.purpose })
      }
      purposeMap.value = map
      purposeOptions.value = options
    }
  } catch {
    // 用途接口失败不影响日志列表；下拉至少可显示原始 purpose
    purposeMap.value = {}
    purposeOptions.value = []
  }
}

async function refreshAll() {
  await Promise.all([loadStats(), loadList()])
}

function onDaysChange() {
  loadStats()
}

function applyFilters() {
  page.value = 1
  loadList()
}

function resetFilters() {
  statusFilter.value = undefined
  purposeFilter.value = undefined
  placeholdersOnly.value = false
  page.value = 1
  loadList()
}

function onPageChange(p: number) {
  page.value = p
  if (placeholdersOnly.value) {
    items.value = slicePage(scannedRows.value || [], p, pageSize.value)
  } else {
    loadList()
  }
}

function onSizeChange(_p: number, size: number) {
  pageSize.value = size
  page.value = 1
  if (placeholdersOnly.value) {
    items.value = slicePage(scannedRows.value || [], 1, size)
  } else {
    loadList()
  }
}

onMounted(async () => {
  await loadPurposes()
  await refreshAll()
})
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.small {
  font-size: 12px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sub-extra {
  color: var(--app-text-secondary);
  font-size: 12px;
  margin-left: 4px;
}
.stats {
  margin-bottom: 14px;
}
.ok-icon {
  background: rgba(47, 158, 68, 0.12);
  color: #2f9e44;
}
.fail-icon {
  background: rgba(224, 49, 49, 0.1);
  color: #e03131;
}
.ok-text {
  color: #2f9e44;
}
.fail-text {
  color: #e03131;
}
.range-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.dist-card {
  background: var(--app-card-bg);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 14px 18px;
  margin-bottom: 14px;
}
.dist-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.dist-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dist-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dist-label {
  width: 132px;
  font-size: 13px;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dist-track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--app-bg);
  overflow: hidden;
}
.dist-bar {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--brand);
  transition: width 0.3s ease;
}
.dist-count {
  width: 56px;
  text-align: right;
  font-size: 12.5px;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
}
.dist-empty {
  font-size: 12.5px;
  padding: 4px 0 2px;
}
.ph-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--app-text-secondary);
  cursor: pointer;
}
.table-card {
  padding: 8px 8px 12px;
}
.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}
/* 空态文案：主标题用正文色（antd 默认 description 继承灰色太浅），副标题隔开 */
.table-card :deep(.ant-empty-description) {
  color: var(--app-text);
}
.table-card :deep(.ant-empty-description .cell-sub) {
  margin-top: 6px;
}
.template-code {
  cursor: help;
  white-space: nowrap;
}
.purpose-tag {
  margin-inline-end: 0;
  font-size: 12px;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;
  white-space: nowrap;
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
.status-pill.sent {
  color: #2f9e44;
}
.status-pill.failed {
  color: #e03131;
}
.var-chips {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}
.var-chip {
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 11px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 5px;
  background: var(--brand-bg);
  color: var(--brand);
  border: 1px solid rgba(229, 113, 83, 0.25);
  white-space: nowrap;
}
.var-chip.more {
  background: transparent;
  color: var(--app-text-secondary);
  border-color: var(--app-border);
  cursor: help;
}
.ph-badge {
  border: 1px solid rgba(224, 49, 49, 0.55);
  background: rgba(224, 49, 49, 0.1);
  color: #e03131;
  border-radius: 10px;
  padding: 0 8px;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
}
.ph-badge:hover {
  background: rgba(224, 49, 49, 0.18);
}
.fail-detail {
  padding: 4px 8px 8px 16px;
}
.fail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.fail-pre {
  margin: 0;
  padding: 12px 14px;
  background: rgba(224, 49, 49, 0.06);
  border: 1px solid rgba(224, 49, 49, 0.25);
  border-radius: 8px;
  color: var(--app-text);
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 280px;
  overflow: auto;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}
[data-theme='dark'] .ok-text,
[data-theme='dark'] .status-pill.sent {
  color: #69db7c;
}
[data-theme='dark'] .fail-text,
[data-theme='dark'] .status-pill.failed {
  color: #ff8787;
}
[data-theme='dark'] .ph-badge {
  color: #ff8787;
  border-color: rgba(255, 135, 135, 0.55);
  background: rgba(255, 135, 135, 0.12);
}
[data-theme='dark'] .ok-icon {
  background: rgba(105, 219, 124, 0.14);
  color: #69db7c;
}
[data-theme='dark'] .fail-icon {
  background: rgba(255, 135, 135, 0.14);
  color: #ff8787;
}
</style>
