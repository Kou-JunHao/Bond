import axios from 'axios'
import { h } from 'vue'
import { Button, notification } from 'ant-design-vue'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { formatApiError, isForbidden } from '../utils/errors'
import type {
  AdminPlan, AdminUser, Announcement, AnnouncementUpsert, ApiResult, AuditLog, CaptchaData, CurrentUser,
  DashboardData, EmailPreview, EmailSettings, EmailSettingsPayload, EmailTemplate, EmailTemplateCreate,
  EmailTemplateSummary, EmailTemplateUpsert,
  EmailTestResult, GrantRequest, LoginResult, MailDeliveryLog, MailDeliveryStats, MailPurpose, PageResult,
  PlanLimitsPatch, RedeemCodesRequest, SetPlanRequest,
  SystemConfigItem, TransferStats, TransferTask, UserStats, WorkspaceDetail, WorkspaceItem
} from '../types'

/**
 * 管理台 API 封装 —— 页面里不要直接写 axios / fetch。
 * 后端统一返回 { code, message, data }，code=200 表示成功。
 */
const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  // 后端 task-144：带这个头才判定为「管理端登录」，并写入 admin_login_logs。
  // 必须对所有请求统一加上，否则登录审计里看不到真实登录来源。
  config.headers['X-Client-App'] = 'bond-admin'
  return config
})

/** 全局 403 提示节流（多个页面同时失败时只弹一次） */
let lastForbiddenNotice = 0

function notifyForbidden(err: unknown) {
  const now = Date.now()
  if (now - lastForbiddenNotice < 5000) return
  lastForbiddenNotice = now
  notification.warning({
    message: '无法访问管理接口',
    description: `${formatApiError(err)}。若持续出现 403，说明当前账号没有管理员权限，请使用管理员账号登录。`,
    duration: 8,
    btn: () =>
      h(Button, { type: 'primary', size: 'small', onClick: () => router.push('/login') }, () => '返回登录')
  })
}

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    if (err.response?.status === 401) {
      const auth = useAuthStore()
      auth.logout()
      const current = router.currentRoute.value
      // 保留当前路由，登录成功后可回到原页面（不要把 redirect 弄丢）
      if (current.path !== '/login') {
        router.push({ path: '/login', query: { redirect: current.fullPath } })
      }
    } else if (isForbidden(err) && !err.config?.headers?.['X-Silence-403']) {
      // 403 不自动登出：可能是某个单独操作没有权限（例如非 owner 改别人的邮箱），
      // 只提示可读文案 + 「返回登录」；持续 403 才说明账号不是管理员。
      notifyForbidden(err)
    }
    return Promise.reject(err)
  }
)

const get = <T>(url: string, params?: Record<string, unknown>) =>
  http.get(url, { params }) as unknown as Promise<ApiResult<T>>
const post = <T>(url: string, data?: unknown) =>
  http.post(url, data) as unknown as Promise<ApiResult<T>>
const put = <T>(url: string, data?: unknown) =>
  http.put(url, data) as unknown as Promise<ApiResult<T>>
const del = <T>(url: string) =>
  http.delete(url) as unknown as Promise<ApiResult<T>>

export default http

export const adminApi = {
  // ── 认证 ──
  login: (username: string, password: string, captchaId?: string, captchaCode?: string) => {
    const payload: Record<string, string> = { username, password }
    if (captchaId) payload.captchaId = captchaId
    if (captchaCode) payload.captchaCode = captchaCode
    return post<LoginResult>('/auth/login', payload)
  },
  getCaptcha: () => get<CaptchaData>('/auth/captcha'),
  checkCaptcha: () => get<{ needsCaptcha: boolean } | boolean>('/auth/captcha/check'),
  getMe: () => get<CurrentUser>('/auth/me'),
  updateProfile: (nickname?: string, avatarUrl?: string) => {
    const body: Record<string, string> = {}
    if (nickname !== undefined) body.nickname = nickname
    if (avatarUrl !== undefined) body.avatarUrl = avatarUrl
    return put<void>('/auth/profile', body)
  },
  changePassword: (oldPassword: string, newPassword: string) =>
    put<void>('/auth/password', { oldPassword, newPassword }),
  uploadAvatar: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/transfer/avatar', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as unknown as Promise<ApiResult<{ url: string }>>
  },

  // ── 总览 ──
  dashboard: () => get<DashboardData>('/admin/dashboard'),
  transferTrend: (days = 7) =>
    get<Record<string, number>>('/admin/dashboard/transfer-trend', { days }),
  userStats: () => get<UserStats>('/admin/users/stats'),
  transferStats: () => get<TransferStats>('/admin/transfers/stats'),

  // ── 用户 ──
  listUsers: (params: { page: number; size: number; keyword?: string }) =>
    get<AdminUser[]>('/admin/users', params),
  getUserDetail: (id: string) => get<AdminUser>(`/admin/users/${id}`),
  setUserStatus: (id: string, status: number) => put<void>(`/admin/users/${id}/status`, { status }),
  setAdmin: (id: string, isAdmin: boolean) => put<void>(`/admin/users/${id}/admin`, { isAdmin }),
  deleteUser: (id: string) => del<void>(`/admin/users/${id}`),
  setUserPlan: (id: string, body: SetPlanRequest) => post<void>(`/admin/users/${id}/plan`, body),
  grantEntitlement: (body: GrantRequest) => post<void>('/admin/grant', body),

  // 邮箱/密码操作：后端 V14 提供（AdminController），未上线时接口返回 404，页面按「接口未上线」提示
  // 改邮箱后端同时注册了 PUT 与 PATCH（task-57），这里用 PUT 与任务书一致；重置密码返回 { delivered, emailMasked }
  resetUserPassword: (id: string) =>
    post<{ delivered?: boolean; emailMasked?: string }>(`/admin/users/${id}/reset-password`),
  updateUserEmail: (id: string, email: string) =>
    put<AdminUser>(`/admin/users/${id}/email`, { email }),

  // ── 兑换码（无列表查询接口，只做生成后展示） ──
  createRedeemCodes: (body: RedeemCodesRequest) =>
    post<string[]>('/admin/redeem-codes', body),

  // ── 传输 ──
  listTransfers: (params: { page: number; size: number; status?: number }) =>
    get<TransferTask[]>('/admin/transfers', params),
  deleteTransfer: (id: string) => del<void>(`/admin/transfers/${id}`),
  cleanupTransfers: () => post<number>('/admin/transfers/cleanup'),

  // ── 工作区 ──
  listWorkspaces: (params: { page: number; size: number }) =>
    get<WorkspaceItem[]>('/admin/workspaces', params),
  getWorkspaceDetail: (id: string) => get<WorkspaceDetail>(`/admin/workspaces/${id}`),
  deleteWorkspace: (id: string) => del<void>(`/admin/workspaces/${id}`),

  // ── 公告（V15） ──
  listAnnouncements: (params: { page: number; size: number }) =>
    get<PageResult<Announcement>>('/admin/announcements', params),
  createAnnouncement: (body: AnnouncementUpsert) =>
    post<Announcement>('/admin/announcements', body),
  updateAnnouncement: (id: string, body: AnnouncementUpsert) =>
    put<Announcement>(`/admin/announcements/${id}`, body),
  deleteAnnouncement: (id: string) => del<void>(`/admin/announcements/${id}`),

  // ── 审计日志（V15） ──
  listAuditLogs: (params: {
    page: number
    size: number
    action?: string
    actorId?: string
    from?: string
    to?: string
  }) => get<PageResult<AuditLog>>('/admin/audit-logs', params),
  listAuditActions: () => get<string[]>('/admin/audit-actions'),

  // ── 系统配置 ──
  listConfig: () => get<SystemConfigItem[]>('/admin/config'),
  getConfigByKey: (key: string) => get<string>(`/admin/config/${key}`),
  updateConfig: (key: string, value: string) => put<void>(`/admin/config/${key}`, { value }),
  batchUpdateConfig: (configs: Record<string, string>) => put<void>('/admin/config', configs),
  resetConfig: () => post<void>('/admin/config/reset'),

  // ── 邮件服务（task-63 / V16；task-76 / V17 增模板新增删除与用途绑定）──
  getEmailSettings: () => get<EmailSettings>('/admin/mail/settings'),
  updateEmailSettings: (body: EmailSettingsPayload) =>
    put<EmailSettings>('/admin/mail/settings', body),
  testEmail: (to: string) => post<EmailTestResult>('/admin/mail/test', { to }),
  listEmailTemplates: () => get<EmailTemplateSummary[]>('/admin/mail/templates'),
  getEmailTemplate: (code: string) => get<EmailTemplate>(`/admin/mail/templates/${code}`),
  createEmailTemplate: (body: EmailTemplateCreate) =>
    post<EmailTemplate>('/admin/mail/templates', body),
  updateEmailTemplate: (code: string, body: EmailTemplateUpsert) =>
    put<EmailTemplate>(`/admin/mail/templates/${code}`, body),
  deleteEmailTemplate: (code: string) => del<void>(`/admin/mail/templates/${code}`),
  resetEmailTemplate: (code: string) =>
    post<EmailTemplate>(`/admin/mail/templates/${code}/reset`),
  previewEmail: (code: string, variables: Record<string, string>) =>
    post<EmailPreview>('/admin/mail/preview', { code, variables }),
  // 用途 ↔ 模板绑定（task-76）
  listMailPurposes: () => get<MailPurpose[]>('/admin/mail/purposes'),
  /** templateCode 为空 = 解绑并恢复内置默认（后端：PUT {} 或 {"templateCode":""} → 200） */
  bindMailPurpose: (purpose: string, templateCode?: string | null) =>
    put<MailPurpose>(`/admin/mail/purposes/${purpose}`, templateCode ? { templateCode } : {}),
  /** 按用途预览（后端按「绑定优先 → 内置回落」解析；不给 code） */
  previewMailByPurpose: (purpose: string, variables: Record<string, string>) =>
    post<EmailPreview>('/admin/mail/preview', { purpose, variables }),

  // ── 邮件投递日志（task-82/83，生产 V18） ──
  /** 分页查询投递记录；status 仅 sent|failed，purpose 取 purposes 的 purpose 值 */
  listMailDelivery: (params: { page: number; size: number; status?: string; purpose?: string }) =>
    get<PageResult<MailDeliveryLog>>(
      '/admin/mail/delivery',
      Object.fromEntries(
        Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
      )
    ),
  /** 投递统计（days 默认 7；failureRate 为百分比 0–100） */
  getMailDeliveryStats: (days = 7) => get<MailDeliveryStats>('/admin/mail/delivery/stats', { days }),

  // ── 档位参数（task-112 / task-114，生产实测） ──
  /** 三个档位全部返回（含 isActive=false），limits 为 JSON 原始数字 */
  listPlans: () => get<AdminPlan[]>('/admin/plans'),
  /** partial update：body 是扁平的 limits 键，只传改动的键；null 表示删除该键；返回更新后的档位 */
  updatePlan: (code: string, patch: PlanLimitsPatch) =>
    put<AdminPlan>(`/admin/plans/${encodeURIComponent(code)}`, patch)
}
