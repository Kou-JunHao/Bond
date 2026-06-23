<template>
  <el-card shadow="never">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <el-input v-model="keyword" placeholder="搜索用户名/昵称" clearable style="width:260px"
                  @clear="loadUsers" @keyup.enter="loadUsers">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
    </template>
    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="180" show-overflow-tooltip />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="nickname" label="昵称" width="120" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="管理员" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.isAdmin" type="warning" size="small">是</el-tag>
          <span v-else style="color:#909399">否</span>
        </template>
      </el-table-column>
      <el-table-column prop="deviceCount" label="设备数" width="70" />
      <el-table-column prop="transferCount" label="传输数" width="70" />
      <el-table-column label="注册时间" width="170">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'"
                     @click="toggleStatus(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button size="small" :type="row.isAdmin ? 'info' : 'primary'"
                     @click="toggleAdmin(row)">
            {{ row.isAdmin ? '取消管理员' : '设为管理员' }}
          </el-button>
          <el-popconfirm title="确认删除此用户？" @confirm="deleteUser(row.id)">
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
                   @current-change="loadUsers" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api/admin'

const users = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)

function formatDate(ts: number) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function loadUsers() {
  loading.value = true
  try {
    const res: any = await adminApi.getUsers({ page: page.value, size: pageSize, keyword: keyword.value || undefined })
    if (res.code === 200) { users.value = res.data || []; total.value = res.data?.length || 0 }
  } finally { loading.value = false }
}

async function toggleStatus(row: any) {
  const newStatus = row.status === 1 ? 0 : 1
  await adminApi.setUserStatus(row.id, newStatus)
  row.status = newStatus
  ElMessage.success('操作成功')
}

async function toggleAdmin(row: any) {
  await adminApi.setAdmin(row.id, !row.isAdmin)
  row.isAdmin = !row.isAdmin
  ElMessage.success('操作成功')
}

async function deleteUser(id: number) {
  await adminApi.deleteUser(id)
  users.value = users.value.filter(u => u.id !== id)
  ElMessage.success('已删除')
}

onMounted(loadUsers)
</script>
