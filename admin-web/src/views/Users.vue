<template>
  <a-card title="用户管理" size="small">
    <template #extra>
      <a-space>
        <a-input-search v-model:value="keyword" placeholder="搜索用户名/昵称" style="width: 240px" @search="loadUsers" allow-clear />
        <a-button @click="loadUsers">刷新</a-button>
      </a-space>
    </template>

    <a-table :columns="columns" :data-source="users" :loading="loading" row-key="id" size="middle" :pagination="{ pageSize: 20, showTotal: (t: number) => `共 ${t} 条` }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '正常' : '禁用' }}</a-tag>
        </template>
        <template v-if="column.key === 'isAdmin'">
          <a-tag v-if="record.isAdmin" color="blue">管理员</a-tag>
          <span v-else style="color: #999">否</span>
        </template>
        <template v-if="column.key === 'createdAt'">
          {{ formatDate(record.createdAt) }}
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a @click="showDetail(record)">详情</a>
            <a @click="toggleStatus(record)">{{ record.status === 1 ? '禁用' : '启用' }}</a>
            <a @click="toggleAdmin(record)">{{ record.isAdmin ? '取消管理员' : '设为管理员' }}</a>
            <a-popconfirm title="确认删除此用户？" @confirm="deleteUser(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-drawer v-model:open="detailVisible" title="用户详情" width="480">
      <a-descriptions v-if="detailUser" :column="1" bordered size="small">
        <a-descriptions-item label="ID">{{ detailUser.id }}</a-descriptions-item>
        <a-descriptions-item label="用户名">{{ detailUser.username }}</a-descriptions-item>
        <a-descriptions-item label="昵称">{{ detailUser.nickname }}</a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="detailUser.status === 1 ? 'green' : 'red'">{{ detailUser.status === 1 ? '正常' : '禁用' }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="管理员">{{ detailUser.isAdmin ? '是' : '否' }}</a-descriptions-item>
        <a-descriptions-item label="注册时间">{{ formatDate(detailUser.createdAt) }}</a-descriptions-item>
      </a-descriptions>
      <a-divider />
      <h4>设备列表</h4>
      <a-table :columns="deviceColumns" :data-source="detailUser?.devices || []" size="small" :pagination="false" row-key="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'isOnline'">
            <a-badge :status="record.isOnline ? 'success' : 'default'" :text="record.isOnline ? '在线' : '离线'" />
          </template>
        </template>
      </a-table>
    </a-drawer>
  </a-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 120 },
  { title: '状态', key: 'status', width: 80 },
  { title: '管理员', key: 'isAdmin', width: 80 },
  { title: '设备数', dataIndex: 'deviceCount', key: 'deviceCount', width: 70 },
  { title: '传输数', dataIndex: 'transferCount', key: 'transferCount', width: 70 },
  { title: '注册时间', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 240, fixed: 'right' }
]

const deviceColumns = [
  { title: '设备名', dataIndex: 'deviceName', key: 'deviceName' },
  { title: '类型', dataIndex: 'deviceType', key: 'deviceType', width: 80 },
  { title: '在线', key: 'isOnline', width: 80 }
]

const users = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const detailVisible = ref(false)
const detailUser = ref<any>(null)

function formatDate(ts: number) { return ts ? new Date(ts).toLocaleString('zh-CN') : '-' }

async function loadUsers() {
  loading.value = true
  try {
    const res: any = await adminApi.getUsers({ page: 1, size: 100, keyword: keyword.value || undefined })
    if (res.code === 200) users.value = res.data || []
  } catch { message.error('加载用户列表失败') }
  finally { loading.value = false }
}

async function toggleStatus(row: any) {
  const oldStatus = row.status
  const newStatus = oldStatus === 1 ? 0 : 1
  row.status = newStatus
  try {
    await adminApi.setUserStatus(row.id, newStatus)
    message.success('操作成功')
  } catch { row.status = oldStatus; message.error('操作失败') }
}

async function toggleAdmin(row: any) {
  const oldIsAdmin = row.isAdmin
  row.isAdmin = !oldIsAdmin
  try {
    await adminApi.setAdmin(row.id, !oldIsAdmin)
    message.success('操作成功')
  } catch { row.isAdmin = oldIsAdmin; message.error('操作失败') }
}

async function deleteUser(id: number) {
  const oldUsers = [...users.value]
  users.value = users.value.filter(u => u.id !== id)
  try {
    await adminApi.deleteUser(id)
    message.success('已删除')
  } catch { users.value = oldUsers; message.error('删除失败') }
}

async function showDetail(row: any) {
  detailVisible.value = true; detailUser.value = null
  try { const res: any = await adminApi.getUserDetail(row.id); if (res.code === 200) detailUser.value = res.data } catch {}
}

onMounted(loadUsers)
</script>
