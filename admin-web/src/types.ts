/**
 * 后端契约类型。
 * 约定：雪花 ID 一律 string（后端用 ToStringSerializer 序列化，前端禁止 Number() 转换）。
 */

/** 雪花 ID —— 后端 JSON 里是字符串，全程按字符串传递 */
export type SnowflakeId = string

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** GET /api/admin/users、GET /api/admin/users/{id} */
export interface AdminUser {
  id: SnowflakeId
  username: string
  nickname: string | null
  avatarUrl: string | null
  /** 邮箱列由后端 V14 提供；未上线时字段缺省 */
  email?: string | null
  status: number
  isAdmin: boolean
  /** 档位字段（task-102 后端已提供） */
  planCode?: string | null
  /** 档位展示名（plans.name，后端可能返回中文；不要前端映射） */
  planName?: string | null
  /** 到期时间（毫秒时间戳；null 表示长期） */
  planExpiresAt?: number | null
  lastLoginAt?: number | null
  createdAt?: number | null
  deviceCount: number
  transferCount: number
}

/** GET /api/admin/dashboard */
export interface DashboardData {
  totalUsers: number
  activeUsers: number
  totalTransfers: number
  completedTransfers: number
  totalWorkspaces: number
  totalStorageUsed: number
  transferTrend: Record<string, number>
}

/** GET /api/admin/users/stats */
export interface UserStats {
  total: number
  active: number
  disabled: number
  admin: number
}

/** GET /api/admin/transfers/stats */
export interface TransferStats {
  total: number
  pending: number
  uploading: number
  completed: number
  failed: number
}

/** GET /api/admin/transfers */
export interface TransferTask {
  id: SnowflakeId
  senderId: SnowflakeId
  receiverId: SnowflakeId
  fileName: string
  fileSize: number
  chunkSize?: number
  chunkCount: number
  uploadedChunks: number
  downloadedChunks?: number
  status: number
  createdAt?: number | null
  expiresAt?: number | null
  progressBytes?: number | null
  totalBytes?: number | null
}

/** GET /api/admin/workspaces（列表） */
export interface WorkspaceItem {
  id: SnowflakeId
  name: string
  description: string | null
  ownerId: SnowflakeId
  maxSize: number
  usedSize: number
  /** 列表接口当前返回 null（聚合未填充），详情接口才有 */
  memberCount?: number | null
  fileCount?: number | null
  createdAt?: number | null
}

/** GET /api/admin/workspaces/{id} */
export interface WorkspaceDetail {
  id: SnowflakeId
  name: string
  description: string | null
  ownerId: SnowflakeId | null
  maxSize: number
  usedSize: number
  memberCount: number
  fileCount: number
}

/** GET /api/admin/config */
export interface SystemConfigItem {
  configKey: string
  configValue: string
  configType: 'string' | 'int' | 'long' | 'boolean' | string
  description?: string | null
  updatedBy?: SnowflakeId | null
  updatedAt?: string | null
}

/** GET /api/admin/announcements（V15） */
export interface Announcement {
  id: SnowflakeId
  title: string
  content: string
  level: 'info' | 'warn' | 'critical' | string
  publishedAtMillis?: number | null
  expiresAtMillis?: number | null
  createdAtMillis?: number | null
  read: boolean
  readCount: number
}

export interface AnnouncementUpsert {
  title: string
  content: string
  level: string
  /** null = 立即发布；未来时间 = 定时发布 */
  publishedAt?: string | null
  /** null = 永不过期 */
  expiresAt?: string | null
}

/** GET /api/admin/audit-logs（V15） */
export interface AuditLog {
  id: SnowflakeId
  actorId?: SnowflakeId | null
  actorName?: string | null
  action: string
  targetType?: string | null
  targetId?: SnowflakeId | null
  detail?: string | null
  ip?: string | null
  createdAtMillis?: number | null
}

export interface SetPlanRequest {
  planCode: string
  /** 与 expiresAt 二选一；都不传默认 1 个月 */
  months?: number
  expiresAt?: string | null
}

export interface GrantRequest {
  userId: SnowflakeId
  key: string
  value: number
  expiresAt?: string | null
  note?: string
}

export interface RedeemCodesRequest {
  planCode: string
  durationDays: number
  count: number
  expiresAt?: string | null
}

/** GET /api/auth/me */
export interface CurrentUser {
  id: SnowflakeId
  username: string
  nickname: string | null
  avatarUrl: string | null
}

export interface LoginResult extends CurrentUser {
  accessToken: string
  refreshToken: string
}

export interface CaptchaData {
  id: string
  image: string
}

/* ───────────── 邮件服务（task-63 / V16）───────────── */

export type EmailProvider = 'smtp' | 'resend' | 'bondmail' | 'off'

export interface EmailSmtpConfig {
  host: string
  port: number
  username: string
  /** 是否已设置密码（只读，后端不回明文） */
  passwordSet?: boolean
  /** 只读掩码：****abcd；留空提交 = 不修改 */
  passwordMasked: string | null
  auth: boolean
  starttls: boolean
  ssl: boolean
  timeoutSeconds: number
}

export interface EmailFromConfig {
  address: string
  name: string
}

export interface EmailTestState {
  at: string
  ok: boolean
  message: string | null
}

/** GET /api/admin/mail/settings（同时注册了 /api/admin/email/settings 别名） */
export interface EmailSettings {
  provider: EmailProvider | string
  /** 实际生效的发信方式（配置不完整时可能回退） */
  effectiveProvider?: string
  from: EmailFromConfig
  smtp: EmailSmtpConfig
  resend: { apiKeySet?: boolean; apiKeyMasked: string | null; endpoint: string }
  bondmail: { endpoint: string }
  configured: boolean
  lastTest: EmailTestState | null
}

/** PUT /api/admin/email/settings —— 密码类字段留空/省略表示不修改 */
export interface EmailSettingsPayload {
  provider: string
  from: EmailFromConfig
  smtp: {
    host: string
    port: number
    username: string
    password?: string
    auth: boolean
    starttls: boolean
    ssl: boolean
    timeoutSeconds: number
  }
  resend: { apiKey?: string; endpoint: string }
  bondmail: { endpoint: string }
}

/** GET /api/admin/email/templates/{code}、列表项 */
export interface EmailTemplate {
  code: string
  name: string
  subject: string
  bodyHtml: string
  bodyText: string | null
  variables: string[]
  enabled: boolean
  isBuiltin: boolean
  updatedAt?: string | null
  updatedBy?: SnowflakeId | null
}

/** GET /api/admin/mail/templates 列表项（不含正文，详情单独取） */
export interface EmailTemplateSummary {
  code: string
  name: string
  subject?: string
  enabled: boolean
  isBuiltin: boolean
  updatedAt?: string | null
}

/** PUT /api/admin/mail/templates/{code}（编辑已有模板；name 不可改 code） */
export interface EmailTemplateUpsert {
  name?: string
  subject: string
  bodyHtml: string
  bodyText?: string | null
  enabled?: boolean
}

/** POST /api/admin/mail/templates（新增自定义模板；code 由后端生成 custom_xxxxxxxx） */
export interface EmailTemplateCreate {
  name: string
  subject: string
  bodyHtml: string
  bodyText?: string | null
  enabled?: boolean
}

/**
 * GET /api/admin/mail/purposes —— 邮件用途与模板绑定关系（task-76 / V17）
 * boundTemplateCode=null 表示未绑定（用内置）；effectiveTemplateCode 为最终生效模板。
 */
export interface MailPurpose {
  purpose: string
  label: string
  builtinTemplateCode: string
  requiredVariables: string[]
  boundTemplateCode: string | null
  effectiveTemplateCode: string
  /** 后端 task-76 修复后补充：绑定了非内置模板时为 true（解绑后立即变 false） */
  overridden?: boolean
}

/** POST /api/admin/mail/preview */
export interface EmailPreview {
  subject?: string
  html: string
  text: string
  /** task-76 后端补充：模板中未被提供的变量（非空时真实发信里这些位置会是空的） */
  missingVariables?: string[]
  /** task-76 后端补充：渲染告警（如变量未知、启用了内置兜底等） */
  warnings?: string[]
}

/** POST /api/admin/email/test */
export interface EmailTestResult {
  ok: boolean
  message: string
}

/** 邮件投递状态（GET /api/admin/mail/delivery） */
export type MailDeliveryStatus = 'sent' | 'failed'

/**
 * GET /api/admin/mail/delivery —— 邮件投递日志（生产 V18，字段以生产返回为准）
 * createdAt 是 ISO 8601（UTC，带 Z），展示时按浏览器本地时区。
 */
export interface MailDeliveryLog {
  /** 雪花 ID：JSON 里是字符串，禁止 Number() */
  id: SnowflakeId
  userId?: SnowflakeId | null
  toMasked: string
  purpose: string
  templateCode: string
  subject: string
  status: MailDeliveryStatus | string
  failureReason: string | null
  variableKeys: string[]
  placeholdersLeft: number
  provider: string
  durationMs: number
  createdAt: string
}

export interface MailDeliveryPurposeStat {
  purpose: string
  total: number
  failed: number
}

/**
 * GET /api/admin/mail/delivery/stats?days=7
 * failureRate 是百分比（0–100，保留 2 位）；task-82 修复后由后端返回。
 */
export interface MailDeliveryStats {
  days?: number
  total: number
  sent: number
  failed: number
  failureRate?: number
  byPurpose?: MailDeliveryPurposeStat[]
}

// ── 软件分发（task-90 / 后端 task-81/85/92） ──────────────────────────────
// 契约以 task-90 rev4 为准：包列表无 id / createdAt；版本 DTO 无 published 布尔字段（publishedAt=null 即未发布）。

/** GET /api/admin/software/packages 的包 DTO */
export interface SoftwarePackage {
  /** 包唯一 code（e.g. bond-app），删除/编辑都以此为键 */
  code: string
  name: string
  description: string | null
  /** 平台字符串：any/windows/macos/linux/android/ios（后端未限制枚举，未知原样显示） */
  platform: string
  /** 最新已发布版本；null 表示当前没有已发布版本 */
  latestVersion: string | null
  /** 已发布版本数量 */
  releaseCount: number
  /** ISO 8601 字符串（后端 LocalDateTime） */
  updatedAt: string
}

/** POST /api/admin/software/packages 的请求体 */
export interface SoftwarePackageCreate {
  code: string
  name: string
  description?: string
  platform?: string
}

/** PUT /api/admin/software/packages/{code} 的请求体（空 body 后端会 400） */
export interface SoftwarePackageUpdate {
  name?: string
  description?: string
  platform?: string
}

/** POST /api/admin/software/releases 的 multipart 字段（query + 文件） */
export interface SoftwareReleaseUpload {
  packageCode: string
  version: string
  channel: string
  releaseNotes?: string
  sha256?: string
  file: File
}

/**
 * GET /api/admin/software/packages/{code}/releases（task-92 新增，含未发布版本）
 * 字段以 task-92 工单为准；publishedAt=null 表示未发布。
 */
export interface SoftwareRelease {
  /** 雪花 ID：JSON 字符串，禁止 Number() */
  id: SnowflakeId
  packageCode: string
  version: string
  channel: string
  fileName: string
  fileSize: number
  sha256: string | null
  releaseNotes: string | null
  downloadCount: number
  publishedAt: string | null
  createdAt: string
}

/** 分享链接状态：0=正常，1=已撤销（已过期是前端按 expiresAt 算出来的，不是后端状态） */
export type ShareStatus = 0 | 1

/**
 * GET /api/admin/shares 的列表项（task-91，字段按 2026-10-24 生产实测）
 * - token 43 字符，页面上只展示前 8 位 + …，不提供完整复制
 * - expiresAt / createdAt / lastAccessAt 均为 epoch 毫秒（可能为 null）
 * - lastAccessAt 后端用 updated_at 近似，UI 文案统一写「最后活动时间」
 */
export interface AdminShareItem {
  /** 雪花 ID：JSON 字符串，禁止 Number() */
  id: SnowflakeId
  token: string
  url: string
  /** 雪花 ID：JSON 字符串，禁止 Number() */
  fileId: SnowflakeId
  fileName: string
  /** 雪花 ID：JSON 字符串，禁止 Number() */
  ownerId: SnowflakeId
  ownerUsername: string
  status: ShareStatus
  expiresAt: number | null
  downloadCount: number
  createdAt: number | null
  lastAccessAt: number | null
}

/** limits 键的值（plans.limits 是 JSON 原始数字，键共 12 个） */
export type PlanLimitKey =
  | 'storage_bytes'
  | 'max_file_size'
  | 'relay_bytes_month'
  | 'relay_down_bytes_per_sec'
  | 'max_workspaces'
  | 'max_workspace_members'
  | 'concurrent_transfers'
  | 'grace_days'
  | 'share_expires_days'
  | 'share_max_downloads'
  | 'share_max_links'
  | 'trash_retention_days'

export type PlanLimits = Partial<Record<PlanLimitKey, number>>

/**
 * GET /api/admin/plans 的档位项（task-112，2026-10-24 生产实测）
 * - limits 原样是 JSON 数字；PUT /api/admin/plans/{code} 的 body 是**扁平** limits 键（不套 layers），
 *   只传要改的键，null 表示删除该键，返回更新后的同一个 DTO。
 */
export interface AdminPlan {
  code: string
  name: string
  priceCents: number | null
  period: string | null
  isActive: boolean | null
  limits: PlanLimits
}

/** PUT /api/admin/plans/{code} 的 body：只含被修改的键 */
export type PlanLimitsPatch = Record<string, number | null>

// ── 安全中心（task-145；后端 task-144 已实现于 bond-transfer，字段按 AdminLogin*DTO 真实定义） ──

/** 登录结果。页面不得伪造任何一项数据。 */
export type LoginOutcome =
  | 'success'
  | 'bad_credentials'
  | 'user_disabled'
  | 'blocked'
  | 'captcha_required'
  | 'error'

/** GET /api/admin/login-logs 单项 = AdminLoginLogDTO */
export interface LoginLogItem {
  id: SnowflakeId
  userId: SnowflakeId | null
  username: string
  ip: string
  userAgent: string | null
  outcome: LoginOutcome
  reason: string | null
  attemptNo: number | null
  /** 毫秒时间戳（后端 createdAtMillis） */
  createdAtMillis: number | null
  /** 该 IP/账号当前是否在封禁期（后端用 admin_login_blocks 实时判定） */
  blockedNow: boolean
}

/** GET /api/admin/login-logs/stats?days=7 = AdminLoginLogStatsDTO */
export interface LoginLogStats {
  days: number
  total: number
  success: number
  failed: number
  byOutcome: Record<string, number>
  topIps: Array<{ ip: string; count: number; failed: number }>
  topUsers: Array<{ username: string; count: number; failed: number }>
  daily: Array<{ date: string; success: number; failed: number }>
}

/** GET /api/admin/login-blocks = AdminLoginBlockDTO */
export interface LoginBlockItem {
  id: SnowflakeId
  /** ip=按 IP 封禁；user=按账号封禁 */
  scope: 'ip' | 'user'
  value: string
  reason: string | null
  failCount: number
  bannedAtMillis: number | null
  expiresAtMillis: number | null
  liftedAtMillis: number | null
  liftedBy: SnowflakeId | null
  /** 后端判定：未解除且未到期 */
  active: boolean
  /** 剩余封禁秒数（后端已按当前时间算好，已到期/已解除为 0） */
  remainingSeconds: number
}

/** GET/PUT /api/admin/login-guard = AdminLoginGuardDTO */
export interface LoginGuardConfig {
  enabled: boolean
  /** 失败计数窗口（秒） */
  failWindowSeconds: number
  /** 同一 IP 失败阈值 */
  failThresholdIp: number
  /** 同一账号失败阈值 */
  failThresholdUser: number
  /** 封禁时长（秒） */
  banSeconds: number
  /** IP 白名单（字符串数组） */
  ipAllowlist: string[]
}

/** PUT /api/admin/login-guard 的 body：扁平字段，缺省不改 */
export interface LoginGuardPatch {
  enabled?: boolean
  failWindowSeconds?: number
  failThresholdIp?: number
  failThresholdUser?: number
  banSeconds?: number
  ipAllowlist?: string[] | string
}
