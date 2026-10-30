<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>传输任务</h2>
        <div class="sub">中继传输任务明细（按创建时间倒序） · 共 {{ stats.total }} 个任务</div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button danger @click="cleanup">
          <template #icon><DeleteOutlined /></template>
          清理过期任务
        </a-button>
      </div>
    </div>

    <div class="stat-chips">
      <button
        v-for="chip in statusChips"
        :key="chip.value ?? 'all'"
        class="stat-chip"
        :class="{ active: statusFilter === chip.value }"
        @click="selectStatus(chip.value)"
      >
        <span class="chip-label">{{ chip.label }}</span>
        <span class="chip-value">{{ chip.count }}</span>
      </button>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      closable
      :message="loadError"
      @close="loadError = ''"
    >
      <template #description>这是后端返回的原始错误，未被改写。</template>
      <template #action>
        <a-button size="small" @click="loadAll">重试</a-button>
      </template>
    </a-alert>

    <div class="content-card table-card">
      <a-table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        row-key="id"
        :pagination="false"
        :scroll="{ x: 1090 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'file'">
            <div class="cell-main ellipsis" style="max-width: 240px">{{ record.fileName || '（无文件名）' }}</div>
            <div class="cell-sub mono">{{ formatBytes(record.fileSize) }}</div>
          </template>

          <template v-else-if="column.key === 'parties'">
            <div class="cell-sub">发送 <span class="mono">{{ record.senderId }}</span></div>
            <div class="cell-sub">接收 <span class="mono">{{ record.receiverId }}</span></div>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="statusInfo(record.status).color">{{ statusInfo(record.status).label }}</a-tag>
          </template>

          <template v-else-if="column.key === 'progress'">
            <div class="progress-wrap">
              <a-progress
                :percent="progressPercent(record as TransferTask)"
                size="small"
                :status="record.status === 3 ? 'exception' : undefined"
                :stroke-color="record.status === 2 ? '#52c41a' : '#e57153'"
              />
              <span class="cell-sub nowrap">
                {{ record.uploadedChunks }}/{{ record.chunkCount }} 分片
                <template v-if="record.downloadedChunks != null"> · 已下载 {{ record.downloadedChunks }}</template>
              </span>
            </div>
          </template>

          <template v-else-if="column.key === 'expiresAt'">
            <span class="nowrap">{{ formatDateTime(record.expiresAt) }}</span>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <span class="nowrap">{{ formatDateTime(record.createdAt) }}</span>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAt) }}</div>
          </template>

          <template v-else-if="column.key === 'actions'">
            <a-button type="link" size="small" danger @click="removeTask(record as TransferTask)">删除</a-button>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="当前筛选条件下没有传输任务">
            <template #description>
              <div class="empty-desc">
                <div class="cell-main">当前筛选条件下没有传输任务</div>
                <div class="cell-sub">任务在客户端发起传输后实时出现；过期任务可点右上角「清理过期任务」</div>
              </div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">第 {{ page }} 页 · 本页 {{ items.length }} 条</span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="paginationTotal"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
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
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { TRANSFER_STATUS } from '../constants/plans'
import { errorText } from '../utils/errors'
import { formatBytes, formatDateTime, fromNow } from '../utils/format'
import type { TransferStats, TransferTask } from '../types'

const loading = ref(false)
const loadError = ref('')
const items = ref<TransferTask[]>([])
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref<number | null>(null)
const stats = ref<TransferStats>({ total: 0, pending: 0, uploading: 0, completed: 0, failed: 0 })

const columns = [
  { title: '文件', key: 'file', width: 260 },
  { title: '发送 / 接收', key: 'parties', width: 180 },
  { title: '状态', key: 'status', width: 96 },
  { title: '进度', key: 'progress', width: 180 },
  { title: '过期时间', key: 'expiresAt', width: 140 },
  { title: '创建时间', key: 'createdAt', width: 150 },
  { title: '操作', key: 'actions', width: 84, fixed: 'right' as const }
]

const statusChips = computed(() => [
  { label: '全部', value: null as number | null, count: stats.value.total },
  { label: '等待中', value: 0, count: stats.value.pending },
  { label: '传输中', value: 1, count: stats.value.uploading },
  { label: '已完成', value: 2, count: stats.value.completed },
  { label: '失败', value: 3, count: stats.value.failed }
])

const paginationTotal = computed(() => {
  const base = (page.value - 1) * pageSize.value + items.value.length
  return items.value.length === pageSize.value ? base + 1 : base
})

function statusInfo(status: number) {
  return TRANSFER_STATUS[status] || { label: `状态 ${status}`, color: 'default' }
}

function progressPercent(task: TransferTask): number {
  if (!task.chunkCount) return 0
  return Math.min(100, Math.round(((task.uploadedChunks || 0) / task.chunkCount) * 100))
}

async function loadStats() {
  try {
    const res = await adminApi.transferStats()
    if (res.code === 200 && res.data) stats.value = res.data
  } catch {
    // 统计失败不阻塞列表
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listTransfers({
      page: page.value,
      size: pageSize.value,
      status: statusFilter.value ?? undefined
    })
    if (res.code === 200) items.value = res.data || []
    else {
      loadError.value = res.message || '传输任务加载失败'
      message.error(res.message || '加载失败')
    }
  } catch (err) {
    loadError.value = errorText(err, '传输任务加载失败')
    message.error(errorText(err, '传输任务加载失败'))
  } finally {
    loading.value = false
  }
}

function selectStatus(value: number | null) {
  statusFilter.value = value
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

async function loadAll() {
  await Promise.all([load(), loadStats()])
}

function removeTask(task: TransferTask) {
  Modal.confirm({
    title: '删除传输任务',
    content: `确定删除任务「${task.fileName || task.id}」？将删除分片记录与存储对象，不可恢复。`,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.deleteTransfer(task.id)
        if (res.code === 200) {
          message.success('任务已删除')
          await loadAll()
        } else {
          message.error(res.message || '删除失败')
        }
      } catch (err) {
        message.error(errorText(err, '删除失败'))
      }
    }
  })
}

function cleanup() {
  Modal.confirm({
    title: '清理过期任务',
    content: '清理所有已过期的未完成任务：删除分片记录与存储对象。已完成的任务不会被清理。',
    okText: '开始清理',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await adminApi.cleanupTransfers()
        if (res.code === 200) {
          message.success(`已清理 ${res.data ?? 0} 个过期任务`)
          await loadAll()
        } else {
          message.error(res.message || '清理失败')
        }
      } catch (err) {
        message.error(errorText(err, '清理失败'))
      }
    }
  })
}

onMounted(loadAll)
</script>

<style scoped>
.small {
  font-size: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stat-chips {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 900px) {
  .stat-chips {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.stat-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  background: var(--app-card-bg);
  cursor: pointer;
  color: inherit;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}
.stat-chip:hover {
  border-color: rgba(229, 113, 83, 0.4);
  transform: translateY(-1px);
}
.stat-chip.active {
  border-color: rgba(229, 113, 83, 0.75);
  box-shadow: 0 8px 22px -14px rgba(229, 113, 83, 0.95);
  background: rgba(229, 113, 83, 0.06);
}
.chip-label {
  font-size: 12px;
  color: var(--app-text-secondary);
}
.chip-value {
  font-size: 22px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.table-card {
  padding: 8px 8px 12px;
}
.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}
.progress-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.progress-wrap :deep(.ant-progress) {
  margin: 0;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}
.empty-desc {
  padding: 18px 0;
}
</style>
