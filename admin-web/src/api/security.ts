import http from './admin'
import type {
  ApiResult,
  LoginBlockItem,
  LoginGuardConfig,
  LoginGuardPatch,
  LoginLogItem,
  LoginLogStats,
  PageResult
} from '../types'

/**
 * 安全中心 API（task-145；后端 task-144 已在 bond-transfer 实现，2026-10-26 按源码契约核对）。
 *
 * 真实契约（字段与 AdminLogin*DTO 一致）：
 *  - GET  /api/admin/login-logs?page&size&ip&username&outcome&from&to
 *          → Result<PageResult<AdminLoginLogDTO>>
 *          outcome ∈ success|bad_credentials|user_disabled|blocked|captcha_required|error
 *          from/to 支持 yyyy-MM-dd 或 ISO-8601；from 缺省=近 30 天
 *  - GET  /api/admin/login-logs/stats?days=7（1..90）
 *          → Result<AdminLoginLogStatsDTO{total,success,failed,byOutcome,topIps,topUsers,daily}>
 *  - GET  /api/admin/login-blocks?active&page&size&scope&value
 *          → Result<PageResult<AdminLoginBlockDTO>>（active/remainingSeconds 由后端算好）
 *  - POST /api/admin/login-blocks/{id}/lift → 204（不存在/已解除 → 404）
 *  - GET  /api/admin/login-guard → Result<AdminLoginGuardDTO>
 *  - PUT  /api/admin/login-guard（扁平 partial：enabled/failWindowSeconds/failThresholdIp/
 *          failThresholdUser/banSeconds/ipAllowlist，ipAllowlist 可数组或逗号字符串）
 *          → Result<AdminLoginGuardDTO>
 *
 * 错误统一走 Result 信封（400/403/404/429），页面展示后端 message 原文。
 */

const asResult = <T>(p: Promise<unknown>) => p as unknown as Promise<ApiResult<T>>

export interface LoginLogQuery {
  page: number
  size: number
  ip?: string
  username?: string
  outcome?: string
  /** yyyy-MM-dd 或 ISO-8601 */
  from?: string
  to?: string
}

export interface LoginBlockQuery {
  active?: boolean
  page: number
  size: number
  scope?: 'ip' | 'user'
  value?: string
}

export const securityApi = {
  /** 登录日志分页（仅管理后台登录尝试） */
  listLoginLogs: (params: LoginLogQuery) =>
    asResult<PageResult<LoginLogItem>>(http.get('/admin/login-logs', { params })),

  /** 近 N 天登录统计（1..90，后端钳制） */
  loginLogStats: (days: number) =>
    asResult<LoginLogStats>(http.get('/admin/login-logs/stats', { params: { days } })),

  /** 封禁列表：active=true 仅当前生效；缺省全部 */
  listLoginBlocks: (params: LoginBlockQuery) =>
    asResult<PageResult<LoginBlockItem>>(http.get('/admin/login-blocks', { params })),

  /** 人工解除封禁（立即生效，幂等失败：不存在/已解除 → 404） */
  liftLoginBlock: (id: string) =>
    asResult<null>(http.post(`/admin/login-blocks/${encodeURIComponent(id)}/lift`)),

  /** 当前登录防护策略 */
  getLoginGuard: () => asResult<LoginGuardConfig>(http.get('/admin/login-guard')),

  /** 部分更新策略（只传要改的字段） */
  updateLoginGuard: (patch: LoginGuardPatch) =>
    asResult<LoginGuardConfig>(http.put('/admin/login-guard', patch))
}

export default securityApi
