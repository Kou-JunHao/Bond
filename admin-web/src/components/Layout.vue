<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon :size="20"><Connection /></el-icon>
        <span>Bond Admin</span>
      </div>
      <el-menu
        :default-active="route.path"
        router
        background-color="#1d1e1f"
        text-color="#a3a6ad"
        active-text-color="#409eff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/transfers">
          <el-icon><Upload /></el-icon>
          <span>传输管理</span>
        </el-menu-item>
        <el-menu-item index="/workspaces">
          <el-icon><FolderOpened /></el-icon>
          <span>工作区管理</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>系统配置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ route.meta.title }}</span>
        <div class="user-info">
          <span>{{ auth.username }}</span>
          <el-button text @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside {
  background: #1d1e1f;
  border-right: solid 1px #333;
  overflow-y: auto;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  color: #e5eaf3;
  font-size: 16px;
  font-weight: 600;
  border-bottom: solid 1px #333;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: solid 1px #e4e7ed;
  background: #fff;
}
.title { font-size: 16px; font-weight: 600; color: #303133; }
.user-info { display: flex; align-items: center; gap: 8px; color: #606266; font-size: 14px; }
.main { background: #f5f7fa; padding: 20px; overflow-y: auto; }
</style>
