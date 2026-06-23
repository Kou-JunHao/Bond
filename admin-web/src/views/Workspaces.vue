<template>
  <a-card title="工作区管理" size="small">
    <template #extra><a-button @click="loadWorkspaces">刷新</a-button></template>

    <a-table :columns="columns" :data-source="workspaces" :loading="loading" row-key="id" size="middle" :pagination="{ pageSize: 20, showTotal: (t: number) => `共 ${t} 条` }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'storage'">
          <a-progress :percent="storagePercent(record)" size="small" :format="() => `${formatSize(record.usedSize)} / ${formatSize(record.maxSize)}`" />
        </template>
        <template v-if="column.key === 'createdAt'">{{ formatDate(record.createdAt) }}</template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a @click="showDetail(record)">详情</a>
            <a-popconfirm title="确认删除此工作区？" @confirm="deleteWorkspace(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-drawer v-model:open="detailVisible" title="工作区详情" width="480">
      <a-descriptions v-if="detail" :column="1" bordered size="small">
        <a-descriptions-item label="ID">{{ detail.id }}</a-descriptions-item>
        <a-descriptions-item label="名称">{{ detail.name }}</a-descriptions-item>
        <a-descriptions-item label="描述">{{ detail.description || '-' }}</a-descriptions-item>
        <a-descriptions-item label="成员数">{{ detail.memberCount ?? '-' }}</a-descriptions-item>
        <a-descriptions-item label="文件数">{{ detail.fileCount ?? '-' }}</a-descriptions-item>
        <a-descriptions-item label="已用空间">{{ formatSize(detail.usedSize) }}</a-descriptions-item>
        <a-descriptions-item label="空间上限">{{ formatSize(detail.maxSize) }}</a-descriptions-item>
      </a-descriptions>
    </a-drawer>
  </a-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '名称', dataIndex: 'name', key: 'name', width: 150 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '存储用量', key: 'storage', width: 240 },
  { title: '创建时间', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 120 }
]

const workspaces = ref<any[]>([])
const loading = ref(false)
const detailVisible = ref(false)
const detail = ref<any>(null)

function formatSize(b: number) { if (!b) return '0 B'; if (b >= 1073741824) return (b/1073741824).toFixed(1)+' GB'; if (b >= 1048576) return (b/1048576).toFixed(1)+' MB'; return (b/1024).toFixed(1)+' KB' }
function formatDate(ts: number) { return ts ? new Date(ts).toLocaleString('zh-CN') : '-' }
function storagePercent(row: any) { return row.maxSize ? Math.round((row.usedSize / row.maxSize) * 100) : 0 }

async function loadWorkspaces() {
  loading.value = true
  try { const res: any = await adminApi.getWorkspaces({ page: 1, size: 100 }); if (res.code === 200) workspaces.value = res.data || [] }
  catch { message.error('加载工作区列表失败') }
  finally { loading.value = false }
}

async function showDetail(row: any) {
  detailVisible.value = true; detail.value = null
  try { const res: any = await adminApi.getWorkspaceDetail(row.id); if (res.code === 200) detail.value = res.data } catch { message.error('获取详情失败') }
}

async function deleteWorkspace(id: number) {
  const oldList = [...workspaces.value]
  workspaces.value = workspaces.value.filter(w => w.id !== id)
  try { await adminApi.deleteWorkspace(id); message.success('已删除') }
  catch { workspaces.value = oldList; message.error('删除失败') }
}

onMounted(loadWorkspaces)
</script>
