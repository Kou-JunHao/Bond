<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>分享链接</h2>
        <div class="sub">
          全站分享链接治理 · 共 {{ total }} 条
          <span class="sub-extra">（已过期为前端按 expiresAt 判定；最后活动时间取 updated_at 近似值）</span>
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="refresh">
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
      message="分享链接接口不可用（404）"
      description="GET /api/admin/shares 返回 404，通常是后端未部署到该版本。接口可用后本页会自动展示真实分享记录。"
    />
    <a-alert
      v-if="loadError && !endpointMissing"
      type="error"
      show-icon
      class="mb16"
      :message="'分享链接加载失败：' + loadError"
      description="这是后端返回的原始错误，未被改写；可点「重试」重新查询。"
    >
      <template #action>
        <a-button size="small" @click="refresh">重试</a-button>
      </template>
    </a-alert>

    <div class="filter-bar">
      <a-select
        v-model:value="statusFilter"
        placeholder="全部状态"
        allow-clear
        style="width: 132px"
        :options="statusOptions"
        @change="applyFilters"
      />
      <a-input
        v-model:value="ownerKeyword"
        placeholder="所属用户名关键词（模糊匹配）"
        allow-clear
        style="width: 230px"
        @pressEnter="applyFilters"
      />
      <a-input
        v-model:value="tokenInput"
        placeholder="token 前缀（前缀匹配）"
        allow-clear
        style="width: 190px"
        @pressEnter="applyFilters"
      />
      <a-button type="primary" @click="applyFilters">查询</a-button>
      <a-button @click="resetFilters">重置</a-button>
      <span class="muted small filter-hint">token 只展示前 8 位，后台不提供完整 token 复制</span>
    </div>

    <div class="content-card table-card">
      <a-table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        row-key="id"
        :pagination="false"
        size="middle"
        table-layout="fixed"
        :scroll="{ x: 1084 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'fileName'">
            <a-tooltip :title="record.fileName">
              <div class="cell-main nowrap file-name">{{ record.fileName || '—' }}</div>
            </a-tooltip>
            <div class="cell-sub nowrap mono">{{ record.fileId }}</div>
          </template>

          <template v-else-if="column.key === 'owner'">
            <div class="cell-main nowrap">{{ record.ownerUsername || '—' }}</div>
            <div class="cell-sub nowrap mono">{{ record.ownerId }}</div>
          </template>

          <template v-else-if="column.key === 'token'">
            <a-tooltip title="token 已收敛，后台只展示前 8 位；不提供完整 token 复制">
              <span class="mono token-prefix">{{ tokenPrefix(record.token) }}</span>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'status'">
            <span class="status-pill" :class="stateClass(record)">
              <span class="status-dot" />
              {{ shareStateLabel(shareStateOf(record)) }}
            </span>
          </template>

          <template v-else-if="column.key === 'expiresAt'">
            <template v-if="record.expiresAt != null">
              <div class="cell-main nowrap">{{ formatDateTime(record.expiresAt, 'YYYY-MM-DD HH:mm') }}</div>
              <div class="cell-sub nowrap">{{ untilNow(record.expiresAt) }}</div>
            </template>
            <span v-else class="muted">永不过期</span>
          </template>

          <template v-else-if="column.key === 'downloadCount'">
            <span class="num">{{ formatNumber(record.downloadCount) }}</span>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <div class="cell-main nowrap">{{ formatDateTime(record.createdAt, 'YYYY-MM-DD HH:mm:ss') }}</div>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAt) }}</div>
          </template>

          <template v-else-if="column.key === 'lastAccessAt'">
            <template v-if="record.lastAccessAt != null">
              <div class="cell-main nowrap">{{ formatDateTime(record.lastAccessAt, 'YYYY-MM-DD HH:mm:ss') }}</div>
              <div class="cell-sub nowrap">{{ fromNow(record.lastAccessAt) }}</div>
            </template>
            <span v-else class="muted">暂无活动</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-button
              v-if="record.status === 0"
              type="link"
              danger
              size="small"
              :disabled="revokingId === record.id"
              :loading="revokingId === record.id"
              @click="confirmRevoke(record)"
            >
              撤销
            </a-button>
            <span v-else class="muted small">—</span>
          </template>
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
          第 {{ page }} 页 · 本页 {{ items.length }} 条 · 共 {{ total }} 条 · 每页最多 100 条
        </span>
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
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { useRoute } from 'vue-router'
import { sharesApi } from '../api/shares'
import { errorStatus, errorText, isEndpointMissing } from '../utils/errors'
import { formatDateTime, formatNumber, fromNow, untilNow } from '../utils/format'
import {
  buildShareParams,
  hasShareFilters,
  revokeErrorText,
  shareEmptyText,
  shareStateLabel,
  shareStateOf,
  tokenPrefix
} from '../utils/shares'
import type { AdminShareItem, ApiResult, ShareStatus } from '../types'

const items = ref<AdminShareItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const revokingId = ref('')
const endpointMissing = ref(false)
const loadError = ref('')
const statusFilter = ref<ShareStatus | undefined>(undefined)
const ownerKeyword = ref('')
const tokenInput = ref('')

const statusOptions = [
  { label: '正常', value: 0 },
  { label: '已撤销', value: 1 }
]

const columns = [
  { title: '文件名', key: 'fileName', width: 200, ellipsis: true },
  { title: '所属用户', key: 'owner', width: 128, ellipsis: true },
  { title: 'token 前缀', key: 'token', width: 96 },
  { title: '状态', key: 'status', width: 88 },
  { title: '过期时间', key: 'expiresAt', width: 132 },
  { title: '下载次数', key: 'downloadCount', width: 76 },
  { title: '创建时间', key: 'createdAt', width: 144 },
  { title: '最后活动时间', key: 'lastAccessAt', width: 144 },
  { title: '操作', key: 'action', width: 76, fixed: 'right' as const }
]

const emptyText = computed(() =>
  shareEmptyText({
    endpointMissing: endpointMissing.value,
    loadError: loadError.value,
    filtered: hasShareFilters({ status: statusFilter.value, ownerKeyword: ownerKeyword.value, tokenPrefix: tokenInput.value })
  })
)

/** 行状态 → 徽标样式类（正常绿 / 已撤销灰 / 已过期橙） */
function stateClass(record: AdminShareItem): string {
  return `state-${shareStateOf(record)}`
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await sharesApi.list(
      buildShareParams(
        { status: statusFilter.value, ownerKeyword: ownerKeyword.value, tokenPrefix: tokenInput.value },
        page.value,
        pageSize.value
      )
    )
    if (res.code !== 200) {
      loadError.value = res.message || `接口返回 code=${res.code}`
      items.value = []
      total.value = 0
      return
    }
    endpointMissing.value = false
    items.value = res.data.items || []
    total.value = res.data.total || 0
  } catch (e) {
    items.value = []
    total.value = 0
    if (isEndpointMissing(e)) {
      endpointMissing.value = true
    } else {
      loadError.value = errorText(e, '列表加载失败')
    }
  } finally {
    loading.value = false
  }
}

function refresh() {
  load()
}

function applyFilters() {
  page.value = 1
  load()
}

function resetFilters() {
  statusFilter.value = undefined
  ownerKeyword.value = ''
  tokenInput.value = ''
  page.value = 1
  load()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  load()
}

function onSizeChange(current: number, size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

/** DELETE 成功是 204（响应体为空），其它情况按 Result 包装判断 */
function resultCode(res: unknown): number {
  if (res && typeof res === 'object' && 'code' in res) return Number((res as ApiResult).code)
  return 204
}

function confirmRevoke(record: AdminShareItem) {
  Modal.confirm({
    title: '撤销这条分享链接？',
    okText: '确认撤销',
    okType: 'danger',
    cancelText: '取消',
    content: `撤销后原链接立即失效，不可恢复。
文件名：${record.fileName || '—'}
所属用户：${record.ownerUsername || '—'}
链接前缀：${tokenPrefix(record.token)}`,
    onOk: () => doRevoke(record)
  })
}

async function doRevoke(record: AdminShareItem) {
  revokingId.value = record.id
  try {
    const res = await sharesApi.revoke(record.id)
    const code = resultCode(res)
    if (code === 200 || code === 204) {
      record.status = 1
      message.success('已撤销，原链接立即失效')
    } else {
      message.error((res as ApiResult).message || `撤销失败（code=${code}）`)
    }
  } catch (e) {
    const status = errorStatus(e)
    const backendMessage = errorText(e, '')
    if (status === 404) {
      // 已不存在/已撤销：把行内状态修正过来，避免反复点
      record.status = 1
      message.error('该链接已不存在或已撤销')
    } else if (isEndpointMissing(e)) {
      message.error('接口未上线（后端待部署），请稍后再试')
    } else {
      message.error(revokeErrorText(status, backendMessage))
    }
  } finally {
    revokingId.value = ''
  }
}

const route = useRoute()

onMounted(() => {
  // 来自用户详情抽屉的「查看全部」：/shares?owner=<username>
  const owner = route.query.owner
  if (typeof owner === 'string' && owner.trim()) {
    ownerKeyword.value = owner.trim()
  }
  load()
})
</script>

<style scoped>
.page-head .sub-extra {
  margin-left: 4px;
  color: var(--app-text-secondary);
}

.mb16 {
  margin-bottom: 16px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.filter-bar {
  flex-wrap: wrap;
}

.filter-hint {
  align-self: center;
}

.table-card {
  padding: 8px 8px 12px;
}

.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}

/* 空态文案：主标题用正文色，副标题隔开 */
.table-card :deep(.ant-empty-description) {
  color: var(--app-text);
}

.table-card :deep(.ant-empty-description .cell-sub) {
  margin-top: 6px;
}

.file-name {
  max-width: 172px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}

/* 列宽收紧后，长文件名/长 id 一律单行省略，避免撑破 1440 布局 */
.cell-main,
.cell-sub {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.token-prefix {
  cursor: help;
  white-space: nowrap;
  font-size: 12.5px;
}

.num {
  font-variant-numeric: tabular-nums;
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

.status-pill.state-normal {
  color: #2f9e44;
}

.status-pill.state-revoked {
  color: var(--app-text-secondary);
}

.status-pill.state-expired {
  color: #d97706;
}

.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}

[data-theme='dark'] .status-pill.state-normal {
  color: #69db7c;
}

[data-theme='dark'] .status-pill.state-expired {
  color: #fbbf24;
}
</style>
