<template>
  <a-layout class="layout">
    <a-layout-sider v-model:collapsed="collapsed" :trigger="null" collapsible breakpoint="lg" :collapsed-width="0"
      :style="{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0, top: 0, bottom: 0, zIndex: 10 }">
      <div class="logo">
        <span class="logo-text" v-if="!collapsed">Bond Admin</span>
        <span class="logo-text" v-else>B</span>
      </div>
      <a-menu v-model:selectedKeys="selectedKeys" theme="dark" mode="inline">
        <a-menu-item key="/dashboard" @click="$router.push('/dashboard')">
          <template #icon><DashboardOutlined /></template>
          仪表盘
        </a-menu-item>
        <a-menu-item key="/users" @click="$router.push('/users')">
          <template #icon><TeamOutlined /></template>
          用户管理
        </a-menu-item>
        <a-menu-item key="/transfers" @click="$router.push('/transfers')">
          <template #icon><CloudUploadOutlined /></template>
          传输管理
        </a-menu-item>
        <a-menu-item key="/workspaces" @click="$router.push('/workspaces')">
          <template #icon><FolderOutlined /></template>
          工作区管理
        </a-menu-item>
        <a-menu-item key="/settings" @click="$router.push('/settings')">
          <template #icon><SettingOutlined /></template>
          系统配置
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout :style="{ marginLeft: collapsed ? '0' : '200px', transition: 'margin-left 0.2s' }">
      <a-layout-header class="header">
        <MenuUnfoldOutlined v-if="collapsed" class="trigger" @click="collapsed = !collapsed" />
        <MenuFoldOutlined v-else class="trigger" @click="collapsed = !collapsed" />
        <div class="header-right">
          <a-dropdown>
            <a-space class="user-info">
              <a-avatar :src="avatarUrl" style="background-color: #1677ff">{{ auth.username?.charAt(0)?.toUpperCase() }}</a-avatar>
              <span class="username">{{ auth.username }}</span>
            </a-space>
            <template #overlay>
              <a-menu @click="handleMenu">
                <a-menu-item key="logout"><LogoutOutlined /> 退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>

      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { adminApi } from '../api/admin'
import {
  DashboardOutlined, TeamOutlined, CloudUploadOutlined,
  FolderOutlined, SettingOutlined, LogoutOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const selectedKeys = ref<string[]>([route.path])
const avatarUrl = ref<string | undefined>(undefined)

watch(() => route.path, (path) => { selectedKeys.value = [path] })

onMounted(async () => {
  try {
    const res: any = await adminApi.getMe()
    if (res.code === 200 && res.data?.avatarUrl) {
      avatarUrl.value = res.data.avatarUrl
    }
  } catch {}
})

function handleMenu({ key }: { key: string }) {
  if (key === 'logout') { auth.logout(); router.push('/login') }
}
</script>

<style scoped>
.layout { min-height: 100vh; }

.logo {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.08);
  margin: 12px;
  border-radius: 6px;
}

.logo-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.header {
  background: #fff;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  position: sticky;
  top: 0;
  z-index: 1;
}

.trigger {
  font-size: 18px;
  cursor: pointer;
  transition: color 0.2s;
  color: #333;
}

.trigger:hover { color: #1677ff; }

.header-right { display: flex; align-items: center; }

.user-info { cursor: pointer; }

.username { font-size: 14px; color: #333; }

.content {
  margin: 24px;
  padding: 24px;
  background: #fff;
  border-radius: 8px;
  min-height: 280px;
}
</style>
