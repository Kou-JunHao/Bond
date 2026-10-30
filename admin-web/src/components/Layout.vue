<template>
  <a-layout class="app-shell">
    <a-layout-sider
      v-model:collapsed="collapsed"
      :width="232"
      :collapsed-width="76"
      :trigger="null"
      collapsible
      breakpoint="lg"
      class="app-sider"
      @breakpoint="onBreakpoint"
    >
      <div class="sider-logo" :class="{ collapsed }" @click="router.push('/dashboard')">
        <img class="logo-mark" :src="logoUrl" alt="Bond" />
        <transition name="fade-text">
          <div v-if="!collapsed" class="logo-text">
            <strong>Bond</strong>
            <span>管理后台</span>
          </div>
        </transition>
      </div>

      <div class="sider-scroll">
        <a-menu
          v-model:selectedKeys="selectedKeys"
          mode="inline"
          :inline-collapsed="collapsed"
          class="side-menu"
          @click="onMenuClick"
        >
          <!-- 收起态：不显示分组标题（antd 会把两字标题压成「总…」「用…」），只留图标 + 中文 tooltip -->
          <template v-if="collapsed">
            <a-menu-item v-for="item in flatMenuItems" :key="item.key">
              <template #icon>
                <a-tooltip placement="right" :title="item.label">
                  <component :is="item.icon" />
                </a-tooltip>
              </template>
              <span>{{ item.label }}</span>
            </a-menu-item>
          </template>
          <!-- 展开态：按分组显示 -->
          <template v-else>
            <a-menu-item-group v-for="group in MENU_GROUPS" :key="group.key" :title="group.title">
              <a-menu-item v-for="item in group.items" :key="item.key">
                <template #icon><component :is="item.icon" /></template>
                <span>{{ item.label }}</span>
              </a-menu-item>
            </a-menu-item-group>
          </template>
        </a-menu>
      </div>

      <div class="sider-foot" :class="{ collapsed }">
        <span class="status-dot" />
        <transition name="fade-text">
          <span v-if="!collapsed && appVersion" class="sider-version">
            v{{ appVersion }}<template v-if="appEnvLabel"> · {{ appEnvLabel }}</template>
          </span>
        </transition>
      </div>
    </a-layout-sider>

    <a-layout class="main-layout">
      <a-layout-header class="app-header">
        <div class="header-left">
          <a-button
            type="text"
            class="collapse-btn"
            :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
            @click="collapsed = !collapsed"
          >
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </a-button>
          <div class="header-title">
            <div class="crumb">{{ currentGroup || 'Bond' }} <span class="sep">/</span> {{ currentTitle }}</div>
            <h1>{{ currentTitle }}</h1>
          </div>
        </div>

        <div class="header-right">
          <a-tooltip :title="theme.mode === 'dark' ? '切换到亮色模式' : '切换到暗色模式'">
            <a-button
              type="text"
              class="icon-btn"
              :aria-label="theme.mode === 'dark' ? '切换到亮色模式' : '切换到暗色模式'"
              @click="theme.toggle()"
            >
              <BulbFilled v-if="theme.mode === 'dark'" />
              <BulbOutlined v-else />
            </a-button>
          </a-tooltip>

          <a-dropdown placement="bottomRight" :trigger="['click']">
            <div class="user-chip">
              <a-avatar :size="30" :src="auth.user?.avatarUrl || undefined" class="user-avatar">
                {{ avatarLetter }}
              </a-avatar>
              <div class="user-meta">
                <strong>{{ auth.displayName }}</strong>
                <span>管理员</span>
              </div>
              <DownOutlined class="user-arrow" />
            </div>
            <template #overlay>
              <a-menu @click="onUserMenu">
                <a-menu-item key="account"><UserOutlined /> 账号信息</a-menu-item>
                <a-menu-item key="api"><ApiOutlined /> 当前 API 状态</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" danger><LogoutOutlined /> 退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>

      <a-layout-content class="app-content">
        <div class="content-inner">
          <router-view v-slot="{ Component }">
            <transition name="fade-page" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </a-layout-content>

      <a-layout-footer class="app-footer">
        <span>Bond 管理后台<span v-if="appVersion"> · v{{ appVersion }}</span></span>
        <span class="footer-sep">·</span>
        <span>数据来自生产 API（bond-api.sg.skkk.uno）</span>
        <span class="footer-grow" />
        <span class="mono footer-build">build {{ buildTag }}</span>
      </a-layout-footer>
    </a-layout>

    <a-modal v-model:open="accountOpen" title="账号信息" :footer="null" width="540">
      <a-descriptions :column="1" size="small" bordered class="account-desc">
        <a-descriptions-item label="用户 ID"><span class="mono">{{ auth.user?.id || '—' }}</span></a-descriptions-item>
        <a-descriptions-item label="用户名">{{ auth.user?.username || '—' }}</a-descriptions-item>
        <a-descriptions-item label="昵称">{{ auth.user?.nickname || '—' }}</a-descriptions-item>
        <a-descriptions-item label="角色"><a-tag color="orange">管理员</a-tag></a-descriptions-item>
        <a-descriptions-item label="当前主题">{{ theme.mode === 'dark' ? '暗色' : '亮色' }}</a-descriptions-item>
      </a-descriptions>

      <a-divider orientation="left" plain>修改密码</a-divider>
      <a-form :model="pwd" layout="vertical" :rules="pwdRules" @finish="changePassword">
        <a-form-item label="当前密码" name="oldPassword">
          <a-input-password v-model:value="pwd.oldPassword" placeholder="请输入当前密码" />
        </a-form-item>
        <a-form-item label="新密码" name="newPassword">
          <a-input-password v-model:value="pwd.newPassword" placeholder="至少 8 位，建议包含大小写与数字" />
        </a-form-item>
        <a-form-item label="确认新密码" name="confirm">
          <a-input-password v-model:value="pwd.confirm" placeholder="再次输入新密码" />
        </a-form-item>
        <a-button type="primary" html-type="submit" :loading="pwdSaving">保存新密码</a-button>
      </a-form>
    </a-modal>
  </a-layout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ApiOutlined, ApartmentOutlined, AuditOutlined, BulbFilled, BulbOutlined, CloudDownloadOutlined, CrownOutlined, DashboardOutlined, DownOutlined,
  GiftOutlined, LogoutOutlined, MailOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  NotificationOutlined, SafetyCertificateOutlined, SendOutlined, SettingOutlined, ShareAltOutlined, SnippetsOutlined, SwapOutlined, TeamOutlined,
  UserOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import logoUrl from '../assets/logo.svg'
import { useAuthStore } from '../stores/auth'
import { useThemeStore } from '../stores/theme'
import { errorText } from '../utils/errors'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()

interface MenuItemDef {
  key: string
  label: string
  icon: Component
}
interface MenuGroupDef {
  key: string
  title: string
  items: MenuItemDef[]
}

/** 侧栏菜单：分组结构（展开态显示分组标题，收起态只渲染 item，避免标题被压成「总…」） */
const MENU_GROUPS: MenuGroupDef[] = [
  {
    key: 'g-overview',
    title: '总览',
    items: [{ key: '/dashboard', label: '数据总览', icon: DashboardOutlined }]
  },
  {
    key: 'g-user',
    title: '用户',
    items: [{ key: '/users', label: '用户管理', icon: TeamOutlined }]
  },
  {
    key: 'g-content',
    title: '内容',
    items: [
      { key: '/workspaces', label: '工作区', icon: ApartmentOutlined },
      { key: '/transfers', label: '传输任务', icon: SwapOutlined },
      { key: '/shares', label: '分享链接', icon: ShareAltOutlined }
    ]
  },
  {
    key: 'g-ops',
    title: '运营',
    items: [
      { key: '/announcements', label: '公告管理', icon: NotificationOutlined },
      { key: '/redeem-codes', label: '兑换码', icon: GiftOutlined }
    ]
  },
  {
    key: 'g-security',
    title: '安全',
    items: [{ key: '/security/login-logs', label: '登录日志', icon: SafetyCertificateOutlined }]
  },
  {
    key: 'g-system',
    title: '系统',
    items: [
      { key: '/audit', label: '审计日志', icon: AuditOutlined },
      { key: '/settings', label: '系统设置', icon: SettingOutlined },
      { key: '/email', label: '邮件服务', icon: MailOutlined },
      { key: '/email/templates', label: '邮件模板', icon: SnippetsOutlined },
      { key: '/mail-delivery', label: '投递日志', icon: SendOutlined },
      { key: '/software', label: '软件分发', icon: CloudDownloadOutlined },
      { key: '/plans', label: '档位管理', icon: CrownOutlined }
    ]
  }
]
const flatMenuItems = computed(() => MENU_GROUPS.flatMap((group) => group.items))

const collapsed = ref(false)
const selectedKeys = ref<string[]>([route.path])
const accountOpen = ref(false)
const pwdSaving = ref(false)
const buildTag = '2026.10'

/** 版本号来自构建期注入（package.json）；取不到就不显示，绝不回退成写死的版本号 */
const appVersion = computed(() => (typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : ''))
const ENV_LABELS: Record<string, string> = {
  production: '生产环境',
  staging: '预发布环境',
  development: '开发环境'
}
/** 环境名同样来自构建期注入（__APP_ENV__），仅做展示映射，不猜环境 */
const appEnvLabel = computed(() => {
  const raw = typeof __APP_ENV__ === 'string' ? __APP_ENV__ : ''
  if (!raw) return ''
  return ENV_LABELS[raw] || raw
})

const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码至少 8 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        value === pwd.newPassword ? Promise.resolve() : Promise.reject('两次输入的密码不一致'),
      trigger: 'blur'
    }
  ]
}

const currentTitle = computed(() => (route.meta.title as string) || '数据总览')
const currentGroup = computed(() => (route.meta.group as string) || '')
const avatarLetter = computed(() => (auth.displayName || 'A').slice(0, 1).toUpperCase())

watch(
  () => route.path,
  (path) => {
    selectedKeys.value = [path]
  }
)

function onBreakpoint(broken: boolean) {
  collapsed.value = broken
}

function onMenuClick({ key }: { key: string }) {
  if (key && key !== route.path) router.push(key)
}

async function refreshMe() {
  if (!auth.token) return
  try {
    const res = await adminApi.getMe()
    if (res.code === 200 && res.data) auth.setUser(res.data)
  } catch {
    // 401 由 axios 拦截器统一处理
  }
}

function onUserMenu({ key }: { key: string }) {
  if (key === 'account') {
    accountOpen.value = true
  } else if (key === 'api') {
    Modal.info({
      title: '当前 API 状态',
      content:
        '管理后台通过同域 /api 反代到网关 8080（bond-api.sg.skkk.uno），鉴权使用登录签发的 JWT（Authorization: Bearer）。',
      okText: '知道了'
    })
  } else if (key === 'logout') {
    Modal.confirm({
      title: '退出登录',
      content: '退出后需要重新输入账号密码才能进入管理后台。',
      okText: '退出',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => {
        auth.logout()
        router.replace('/login')
      }
    })
  }
}

async function changePassword() {
  pwdSaving.value = true
  try {
    await adminApi.changePassword(pwd.oldPassword, pwd.newPassword)
    message.success('密码已修改，请使用新密码重新登录')
    auth.logout()
    router.replace('/login')
  } catch (err) {
    message.error(errorText(err, '修改密码失败'))
  } finally {
    pwdSaving.value = false
  }
}

onMounted(() => {
  refreshMe()
})
</script>

<style scoped>
.app-shell { min-height: 100vh; }

.app-sider {
  position: sticky; top: 0; height: 100vh; overflow: hidden;
  border-right: 1px solid var(--app-border);
  background: var(--app-card-bg) !important;
  display: flex; flex-direction: column; z-index: 20;
}
/* antd 在 Sider 里包了一层 .ant-layout-sider-children，必须把 flex 链补到它身上，
   否则内部 .sider-scroll 的 flex:1/overflow 不生效（表现为「能看见但从不能滚动」） */
.app-sider :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.sider-logo { display: flex; align-items: center; gap: 12px; height: 64px; padding: 0 20px; cursor: pointer; user-select: none; border-bottom: 1px solid var(--app-border); flex: none; }
.sider-logo.collapsed { padding: 0; justify-content: center; }
.logo-mark {
  width: 30px; height: 30px; object-fit: contain; flex: none; display: block;
}
.logo-text { display: flex; flex-direction: column; line-height: 1.15; overflow: hidden; white-space: nowrap; }
.logo-text strong { font-size: 16px; letter-spacing: 0.4px; color: var(--app-text); }
.logo-text span { font-size: 11px; color: var(--app-text-secondary); letter-spacing: 1.5px; }

.sider-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 0 8px;
  /* 细滚动条：避免 11 项菜单在 1440×700 下裁掉下半截 */
  scrollbar-width: thin;
  scrollbar-color: rgba(120, 120, 120, 0.35) transparent;
}
.sider-scroll::-webkit-scrollbar { width: 6px; }
.sider-scroll::-webkit-scrollbar-track { background: transparent; }
.sider-scroll::-webkit-scrollbar-thumb {
  background: rgba(120, 120, 120, 0.3);
  border-radius: 3px;
}
.sider-scroll:hover::-webkit-scrollbar-thumb { background: rgba(120, 120, 120, 0.5); }
.side-menu { border-inline-end: none !important; background: transparent; }
.side-menu :deep(.ant-menu-item-group-title) { padding-inline: 24px; letter-spacing: 1px; }

.sider-foot { flex: none; height: 48px; display: flex; align-items: center; gap: 10px; padding: 0 22px; border-top: 1px solid var(--app-border); color: var(--app-text-secondary); font-size: 12px; overflow: hidden; }
.sider-foot.collapsed { justify-content: center; padding: 0; }
.status-dot { width: 7px; height: 7px; flex: none; border-radius: 50%; background: #52c41a; box-shadow: 0 0 0 4px rgba(82, 196, 26, 0.14); }
.sider-version { white-space: nowrap; }

.main-layout { min-width: 0; background: var(--app-bg); }
.app-header {
  position: sticky; top: 0; z-index: 15; height: 64px; padding: 0 20px 0 12px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  line-height: normal; border-bottom: 1px solid var(--app-border);
  background: var(--app-card-bg); /* 显式实色底，避免回落到 antd 默认深蓝头部 */
}
.header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.collapse-btn { color: var(--app-text-secondary); font-size: 16px; flex: none; }
.header-title { min-width: 0; }
.header-title .crumb { font-size: 11.5px; color: var(--app-text-secondary); letter-spacing: 0.3px; line-height: 1.2; }
.header-title .crumb .sep { color: var(--app-text-secondary); margin: 0 2px; }
.header-title h1 { margin: 1px 0 0; font-size: 17px; font-weight: 600; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.header-right { display: flex; align-items: center; gap: 2px; flex: none; height: 44px; }
.icon-btn { color: var(--app-text-secondary); font-size: 16px; width: 36px; height: 36px; }
.icon-btn:hover { color: var(--app-text); }
.user-chip { display: flex; align-items: center; gap: 9px; height: 40px; padding: 0 10px 0 6px; border-radius: 999px; cursor: pointer; border: 1px solid transparent; transition: background 0.2s ease, border-color 0.2s ease; }
.user-chip:hover { background: rgba(127, 127, 127, 0.08); border-color: var(--app-border); }
.user-avatar { background: var(--brand); color: #2b1a12; font-weight: 600; flex: none; }
.user-meta { display: flex; flex-direction: column; line-height: 1.25; max-width: 150px; min-width: 0; }
.user-meta strong { font-size: 13px; font-weight: 600; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-meta span { font-size: 11px; color: var(--app-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-arrow { font-size: 10px; color: var(--app-text-secondary); flex: none; height: 10px; }

.app-content { padding: 22px 24px 8px; }
.content-inner { width: 100%; max-width: var(--app-content-max); margin: 0 auto; min-height: calc(100vh - 64px - 48px - 30px); }
.app-footer { padding: 10px 24px 16px; background: transparent; color: var(--app-text-secondary); font-size: 12px; display: flex; align-items: center; gap: 8px; }
.footer-sep { color: var(--app-text-secondary); }
.footer-grow { flex: 1; }
.footer-build { opacity: 0.7; }
.account-desc { margin-top: 4px; }

.fade-text-enter-active, .fade-text-leave-active { transition: opacity 0.15s ease; }
.fade-text-enter-from, .fade-text-leave-to { opacity: 0; }

@media (max-width: 640px) {
  .app-content { padding: 14px 12px 6px; }
  .user-meta { display: none; }
  .app-footer { padding: 8px 12px 14px; }
}
</style>
