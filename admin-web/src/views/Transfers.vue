<template>
  <el-card shadow="never">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width:140px" @change="loadTransfers">
          <el-option label="全部" value="" />
          <el-option label="待处理" :value="0" />
          <el-option label="上传中" :value="1" />
          <el-option label="已完成" :value="2" />
          <el-option label="失败" :value="3" />
        </el-select>
        <el-popconfirm title="确认清理所有过期任务？" @confirm="cleanup">
          <template #reference>
            <el-button type="warning"><el-icon><Delete /></el-icon> 清理过期</el-button>
          </template>
        </el-popconfirm>
      </div>
    </template>
    <el-table :data="transfers" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="180" show-overflow-tooltip />
      <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
      <el-table-column label="文件大小" width="100">
        <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="100">
        <template #default="{ row }">
          <span v-if="row.chunkCount">{{ row.uploadedChunks }}/{{ row.chunkCount }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-popconfirm title="确认删除？" @confirm="deleteTransfer(row.id)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:16px;justify-content:flex-end" background
                   layout="total, prev, pager, next"
                   :total="total" :page-size="pageSize" v-model:current-page="page"
                   @current-change="loadTransfers" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api/admin'

const transfers = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref<any>('')
const page = ref(1)
const pageSize = 20
const total = ref(0)

const statusMap: Record<number, { text: string; type: string }> = {
  0: { text: '待处理', type: 'info' },
  1: { text: '上传中', type: '' },
  2: { text: '已完成', type: 'success' },
  3: { text: '失败', type: 'danger' },
  4: { text: '已过期', type: 'warning' }
}
function statusText(s: number) { return statusMap[s]?.text || '未知' }
function statusType(s: number) { return (statusMap[s]?.type || 'info') as any }
function formatSize(b: number) {
  if (!b) return '0 B'
  if (b >= 1073741824) return (b / 1073741824).toFixed(1) + ' GB'
  if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB'
  return (b / 1024).toFixed(1) + ' KB'
}
function formatDate(ts: number) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function loadTransfers() {
  loading.value = true
  try {
    const params: any = { page: page.value, size: pageSize }
    if (statusFilter.value !== '') params.status = statusFilter.value
    const res: any = await adminApi.getTransfers(params)
    if (res.code === 200) { transfers.value = res.data || []; total.value = res.data?.length || 0 }
  } finally { loading.value = false }
}

async function deleteTransfer(id: number) {
  await adminApi.deleteTransfer(id)
  transfers.value = transfers.value.filter(t => t.id !== id)
  ElMessage.success('已删除')
}

async function cleanup() {
  const res: any = await adminApi.cleanupTransfers()
  ElMessage.success(`已清理 ${res.data || 0} 个过期任务`)
  loadTransfers()
}

onMounted(loadTransfers)
</script>
