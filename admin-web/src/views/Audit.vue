<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <div class="sub">后台管理操作的审计记录 · 共 {{ total }} 条</div>
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
      message="审计日志接口不可用（404）"
      description="GET /api/admin/audit-logs 与 /api/admin/audit-actions 返回 404，通常是后端未部署到该版本。接口可用后本页会自动展示真实审计记录。"
    />
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      :message="'审计日志加载失败：' + loadError"
      description="这是后端返回的原始错误，未被改写；可点「重试」重新查询。"
    >
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="filter-bar">
      <a-select
        v-model:value="actionFilter"
        placeholder="全部操作类型"
        allow-clear
        style="width: 220px"
        :options="actionOptions"
        @change="applyFilters"
      />
      <a-input v-model:value="actorFilter" placeholder="操作人 ID（雪花 ID）" allow-clear style="width: 200px" @press-enter="applyFilters" />
      <a-range-picker
        v-model:value="timeRange"
        show-time
        format="YYYY-MM-DD HH:mm"
        :placeholder="['开始时间', '结束时间']"
        style="width: 360px"
        @change="applyFilters"
      />
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
        :scroll="{ x: 1060 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            <span class="nowrap">{{ formatDateTime(record.createdAtMillis) }}</span>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAtMillis) }}</div>
          </template>

          <template v-else-if="column.key === 'actor'">
            <div class="cell-main" :class="{ muted: !record.actorName }">
              {{ record.actorName || '系统/未知' }}
            </div>
            <div class="cell-sub mono">{{ record.actorId || '—' }}</div>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-tag color="blue">{{ auditActionLabel(record.action) }}</a-tag>
            <div class="cell-sub mono">{{ record.action }}</div>
          </template>

          <template v-else-if="column.key === 'target'">
            <div class="cell-main">{{ record.targetType || '—' }}</div>
            <div class="cell-sub mono">{{ record.targetId || '' }}</div>
          </template>

          <template v-else-if="column.key === 'detail'">
            <a-tooltip v-if="record.detail" :title="record.detail">
              <span class="detail-text">{{ record.detail }}</span>
            </a-tooltip>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'ip'">
            <span class="mono">{{ record.ip || '—' }}</span>
          </template>
        </template>

        <template #emptyText>
          <a-empty :description="endpointMissing ? '接口未上线，暂无审计数据' : '没有匹配的审计记录'">
            <template #description>
              <div class="cell-main">{{ endpointMissing ? '接口未上线，暂无审计数据' : '没有匹配的审计记录' }}</div>
              <div class="cell-sub">
                {{ endpointMissing ? '后端部署该版本后刷新页面即可查看' : '调整时间范围或清空筛选条件后重试' }}
              </div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">第 {{ page }} 页 · 本页 {{ items.length }} 条 · 共 {{ total }} 条</span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="total"
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
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { AUDIT_ACTIONS, auditActionLabel } from '../constants/plans'
import { errorText, isEndpointMissing } from '../utils/errors'
import { formatDateTime, fromNow } from '../utils/format'
import type { AuditLog } from '../types'

const loading = ref(false)
const items = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const actionFilter = ref<string | undefined>(undefined)
const actorFilter = ref('')
const timeRange = ref<unknown>(null)
const endpointMissing = ref(false)
const loadError = ref('')
const actionOptions = ref<Array<{ label: string; value: string }>>([])

const columns = [
  { title: '时间', key: 'createdAt', width: 150 },
  { title: '操作人', key: 'actor', width: 190 },
  { title: '操作', key: 'action', width: 170 },
  { title: '目标', key: 'target', width: 130 },
  { title: '详情', key: 'detail', width: 300 },
  { title: 'IP', key: 'ip', width: 120 }
]

/** dayjs 值 → OffsetDateTime 字符串（+08:00 本地偏移），后端按 String 接收 */
function toIso(v: unknown): string | undefined {
  const d = v as { format?: (f: string) => string } | undefined
  if (!d || typeof d.format !== 'function') return undefined
  return d.format('YYYY-MM-DDTHH:mm:ssZ')
}

function buildActionOptions(actions: string[]) {
  const known = new Set(actions)
  const options = Object.keys(AUDIT_ACTIONS).map((key) => ({
    label: AUDIT_ACTIONS[key],
    value: key
  }))
  for (const key of known) {
    if (!AUDIT_ACTIONS[key]) options.push({ label: key, value: key })
  }
  actionOptions.value = options
}

async function loadActions() {
  try {
    const res = await adminApi.listAuditActions()
    if (res.code === 200 && Array.isArray(res.data)) buildActionOptions(res.data)
    else buildActionOptions([])
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
      buildActionOptions([])
    }
  }
}

async function load() {
  loading.value = true
  try {
    const res = await adminApi.listAuditLogs({
      page: page.value,
      size: pageSize.value,
      action: actionFilter.value || undefined,
      actorId: actorFilter.value.trim() || undefined,
      from: toIso(Array.isArray(timeRange.value) ? timeRange.value[0] : undefined),
      to: toIso(Array.isArray(timeRange.value) ? timeRange.value[1] : undefined)
    })
    if (res.code === 200) {
      items.value = res.data?.items || []
      total.value = res.data?.total || 0
      endpointMissing.value = false
      loadError.value = ''
    } else {
      loadError.value = res.message || `接口返回 code=${res.code}`
      message.error(res.message || '加载失败')
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      endpointMissing.value = true
      items.value = []
      total.value = 0
      loadError.value = ''
    } else {
      loadError.value = errorText(err, '审计日志加载失败')
      message.error(loadError.value)
    }
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  load()
}

function resetFilters() {
  actionFilter.value = undefined
  actorFilter.value = ''
  timeRange.value = null
  page.value = 1
  load()
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(_p: number, size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

onMounted(async () => {
  await loadActions()
  await load()
})
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.table-card {
  padding: 8px 8px 12px;
}
.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}
.detail-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--app-text-secondary);
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}
</style>
