import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    group?: string
    requiresAuth?: boolean
  }
}

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    redirect: '/dashboard',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '数据总览', group: '总览' }
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('../views/Users.vue'),
        meta: { title: '用户管理', group: '用户' }
      },
      {
        path: 'workspaces',
        name: 'Workspaces',
        component: () => import('../views/Workspaces.vue'),
        meta: { title: '工作区', group: '内容' }
      },
      {
        path: 'transfers',
        name: 'Transfers',
        component: () => import('../views/Transfers.vue'),
        meta: { title: '传输任务', group: '内容' }
      },
      {
        path: 'shares',
        name: 'Shares',
        component: () => import('../views/Shares.vue'),
        meta: { title: '分享链接', group: '内容' }
      },
      {
        path: 'announcements',
        name: 'Announcements',
        component: () => import('../views/Announcements.vue'),
        meta: { title: '公告管理', group: '运营' }
      },
      {
        path: 'redeem-codes',
        name: 'RedeemCodes',
        component: () => import('../views/RedeemCodes.vue'),
        meta: { title: '兑换码', group: '运营' }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('../views/Audit.vue'),
        meta: { title: '审计日志', group: '系统' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('../views/Settings.vue'),
        meta: { title: '系统设置', group: '系统' }
      },
      {
        path: 'email',
        name: 'EmailSettings',
        component: () => import('../views/EmailSettings.vue'),
        meta: { title: '邮件服务', group: '系统' }
      },
      {
        path: 'email/templates',
        name: 'EmailTemplates',
        component: () => import('../views/EmailTemplates.vue'),
        meta: { title: '邮件模板', group: '系统' }
      },
      {
        path: 'mail-delivery',
        name: 'MailDelivery',
        component: () => import('../views/MailDelivery.vue'),
        meta: { title: '投递日志', group: '系统' }
      },
      {
        path: 'software',
        name: 'Software',
        component: () => import('../views/Software.vue'),
        meta: { title: '软件分发', group: '系统' }
      },
      {
        path: 'plans',
        name: 'Plans',
        component: () => import('../views/Plans.vue'),
        meta: { title: '档位管理', group: '系统' }
      },
      {
        path: 'security/login-logs',
        name: 'SecurityLoginLogs',
        component: () => import('../views/SecurityLoginLogs.vue'),
        meta: { title: '登录日志', group: '安全' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  // 后台部署在站点根目录（nginx root=/www/sites/bond-admin/index，try_files 回退 index.html）
  history: createWebHistory('/'),
  routes
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.token) {
    next({ path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined })
  } else if (to.path === '/login' && auth.token) {
    next('/dashboard')
  } else {
    next()
  }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Bond 管理后台` : 'Bond 管理后台'
})

export default router
