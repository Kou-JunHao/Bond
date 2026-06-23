<template>
  <div>
    <a-row :gutter="16" style="margin-bottom: 16px">
      <a-col :xs="24" :lg="12">
        <a-card title="传输状态分布" size="small">
          <div ref="chartRef" style="height: 250px"></div>
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="12">
        <a-card title="传输统计" size="small">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item v-for="s in statItems" :key="s.key" :label="s.label">{{ s.value }}</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
    </a-row>

    <a-card title="传输任务" size="small">
      <template #extra>
        <a-space>
          <a-select v-model:value="statusFilter" placeholder="状态筛选" allow-clear style="width: 120px" @change="loadTransfers">
            <a-select-option value="">全部</a-select-option>
            <a-select-option :value="0">待处理</a-select-option>
            <a-select-option :value="1">上传中</a-select-option>
            <a-select-option :value="2">已完成</a-select-option>
            <a-select-option :value="3">失败</a-select-option>
          </a-select>
          <a-popconfirm title="确认清理所有过期任务？" @confirm="cleanup">
            <a-button danger>清理过期</a-button>
          </a-popconfirm>
        </a-space>
      </template>

      <a-table :columns="columns" :data-source="transfers" :loading="loading" row-key="id" size="middle" :pagination="{ pageSize: 20, showTotal: (t: number) => `共 ${t} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'fileSize'">{{ formatSize(record.fileSize) }}</template>
          <template v-if="column.key === 'status'">
            <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
          </template>
          <template v-if="column.key === 'progress'">
            {{ record.chunkCount ? `${record.uploadedChunks}/${record.chunkCount}` : '-' }}
          </template>
          <template v-if="column.key === 'createdAt'">{{ formatDate(record.createdAt) }}</template>
          <template v-if="column.key === 'action'">
            <a-popconfirm title="确认删除？" @confirm="deleteTransfer(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
  { title: '大小', key: 'fileSize', width: 100 },
  { title: '状态', key: 'status', width: 90 },
  { title: '进度', key: 'progress', width: 100 },
  { title: '创建时间', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 80 }
]

const transfers = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref<any>(undefined)
const chartRef = ref<HTMLElement>()
const transferStats = ref<any>({})
let chart: echarts.ECharts | null = null

const statItems = ref([
  { label: '总任务数', key: 'total', value: 0 },
  { label: '待处理', key: 'pending', value: 0 },
  { label: '上传中', key: 'uploading', value: 0 },
  { label: '已完成', key: 'completed', value: 0 },
  { label: '失败', key: 'failed', value: 0 },
  { label: '已过期', key: 'expired', value: 0 }
])

const statusTextMap: Record<number, string> = { 0: '待处理', 1: '上传中', 2: '已完成', 3: '失败', 4: '已过期' }
const statusColorMap: Record<number, string> = { 0: 'default', 1: 'processing', 2: 'success', 3: 'error', 4: 'warning' }
function statusText(s: number) { return statusTextMap[s] || '未知' }
function statusColor(s: number) { return statusColorMap[s] || 'default' }
function formatSize(b: number) { if (!b) return '0 B'; if (b >= 1073741824) return (b/1073741824).toFixed(1)+' GB'; if (b >= 1048576) return (b/1048576).toFixed(1)+' MB'; return (b/1024).toFixed(1)+' KB' }
function formatDate(ts: number) { return ts ? new Date(ts).toLocaleString('zh-CN') : '-' }

function updateChart() {
  if (!chartRef.value || !transferStats.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: { trigger: 'item' }, legend: { bottom: 0 },
    color: ['#1677ff', '#722ed1', '#52c41a', '#ff4d4f', '#8c8c8c'],
    series: [{ type: 'pie', radius: ['40%', '70%'], data: [
      { value: transferStats.value.pending ?? 0, name: '待处理' },
      { value: transferStats.value.uploading ?? 0, name: '上传中' },
      { value: transferStats.value.completed ?? 0, name: '已完成' },
      { value: transferStats.value.failed ?? 0, name: '失败' },
      { value: transferStats.value.expired ?? 0, name: '已过期' }
    ]}]
  })
}

async function loadTransfers() {
  loading.value = true
  try {
    const params: any = { page: 1, size: 100 }
    if (statusFilter.value !== undefined && statusFilter.value !== '') params.status = statusFilter.value
    const res: any = await adminApi.getTransfers(params)
    if (res.code === 200) transfers.value = res.data || []
  } finally { loading.value = false }
}

async function loadStats() {
  const res: any = await adminApi.getTransferStats()
  if (res.code === 200) {
    transferStats.value = res.data || {}
    statItems.value.forEach(i => { i.value = (transferStats.value as any)[i.key] ?? 0 })
    updateChart()
  }
}

async function deleteTransfer(id: number) {
  const oldList = [...transfers.value]
  transfers.value = transfers.value.filter(t => t.id !== id)
  try { await adminApi.deleteTransfer(id); message.success('已删除') }
  catch { transfers.value = oldList; message.error('删除失败') }
}
async function cleanup() { try { await adminApi.cleanupTransfers(); loadTransfers(); message.success('清理完成') } catch { message.error('清理失败') } }
function handleResize() { chart?.resize() }

onMounted(async () => { await loadTransfers(); await loadStats(); window.addEventListener('resize', handleResize) })
onUnmounted(() => { window.removeEventListener('resize', handleResize); chart?.dispose() })
</script>
