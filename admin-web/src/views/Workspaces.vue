<template>
  <el-card shadow="never">
    <el-table :data="workspaces" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="180" show-overflow-tooltip />
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="存储用量" width="140">
        <template #default="{ row }">
          <el-progress :percentage="Math.min(100, row.usedSize / row.maxSize * 100)" :stroke-width="14"
                       :format="() => formatSize(row.usedSize)" />
        </template>
      </el-table-column>
      <el-table-column label="上限" width="90">
        <template #default="{ row }">{{ formatSize(row.maxSize) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="showDetail(row.id)">详情</el-button>
          <el-popconfirm title="确认删除此工作区及其所有文件？" @confirm="deleteWs(row.id)">
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
                   @current-change="loadWorkspaces" />

    <el-dialog v-model="dialogVisible" title="工作区详情" width="400px">
      <el-descriptions :column="1" border v-if="detail">
        <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="描述">{{ detail.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="成员数">{{ detail.memberCount }}</el-descriptions-item>
        <el-descriptions-item label="文件数">{{ detail.fileCount }}</el-descriptions-item>
        <el-descriptions-item label="已用">{{ formatSize(detail.usedSize) }}</el-descriptions-item>
        <el-descriptions-item label="上限">{{ formatSize(detail.maxSize) }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api/admin'

const workspaces = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const dialogVisible = ref(false)
const detail = ref<any>(null)

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

async function loadWorkspaces() {
  loading.value = true
  try {
    const res: any = await adminApi.getWorkspaces({ page: page.value, size: pageSize })
    if (res.code === 200) { workspaces.value = res.data || []; total.value = res.data?.length || 0 }
  } finally { loading.value = false }
}

async function showDetail(id: number) {
  const res: any = await adminApi.getWorkspaceDetail(id)
  if (res.code === 200) { detail.value = res.data; dialogVisible.value = true }
}

async function deleteWs(id: number) {
  await adminApi.deleteWorkspace(id)
  workspaces.value = workspaces.value.filter(w => w.id !== id)
  ElMessage.success('已删除')
}

onMounted(loadWorkspaces)
</script>
