import type { AdminShareItem } from '../types'

/**
 * 分享治理页的纯函数（task-91）：状态判定、筛选参数清洗、文案。
 * 抽出来是为了能单独跑 node --test，不依赖 antd / axios。
 */

/** token 只展示前 8 位（token 共 43 字符，后端已做收敛） */
export const TOKEN_PREFIX_LEN = 8

/** 后端分页上限（size <= 100） */
export const SHARE_PAGE_SIZE_MAX = 100
export const SHARE_PAGE_SIZE_DEFAULT = 20

export type ShareRowState = 'normal' | 'revoked' | 'expired'

export interface ShareFilters {
  status?: 0 | 1
  ownerKeyword?: string
  tokenPrefix?: string
}

export interface ShareQuery {
  page: number
  size: number
  status?: 0 | 1
  ownerKeyword?: string
  tokenPrefix?: string
}

const STATE_LABEL: Record<ShareRowState, string> = {
  normal: '正常',
  revoked: '已撤销',
  expired: '已过期'
}

/** token 前缀展示：短 token 原样返回，空值给 — */
export function tokenPrefix(token: string | null | undefined, len = TOKEN_PREFIX_LEN): string {
  if (!token) return '—'
  if (token.length <= len) return token
  return `${token.slice(0, len)}…`
}

/**
 * 行状态：
 * - status=1 → 已撤销（后端权威状态）
 * - status=0 且 expiresAt < now → 已过期（前端按时间算，后端不落库）
 * - 其余 → 正常
 */
export function shareStateOf(
  item: Pick<AdminShareItem, 'status' | 'expiresAt'>,
  now: number = Date.now()
): ShareRowState {
  if (item.status === 1) return 'revoked'
  if (item.expiresAt != null && item.expiresAt < now) return 'expired'
  return 'normal'
}

export function shareStateLabel(state: ShareRowState): string {
  return STATE_LABEL[state]
}

/** 已撤销的行不可再撤销；正常/已过期都可撤销（过期链接也应能手动失效） */
export function isRevocable(item: Pick<AdminShareItem, 'status'>): boolean {
  return item.status === 0
}

/**
 * 筛选条件 → 请求参数：去空白、空值不发、size 夹在 1..100、page 至少 1。
 * 后端 ownerKeyword 是 ILIKE 模糊、tokenPrefix 是前缀，由后端实现，前端不预过滤。
 */
export function buildShareParams(filters: ShareFilters, page: number, size: number): ShareQuery {
  const cleanPage = Math.max(1, Math.floor(Number(page) || 1))
  const cleanSize = Math.min(SHARE_PAGE_SIZE_MAX, Math.max(1, Math.floor(Number(size) || SHARE_PAGE_SIZE_DEFAULT)))
  const query: ShareQuery = { page: cleanPage, size: cleanSize }
  if (filters.status === 0 || filters.status === 1) query.status = filters.status
  const owner = (filters.ownerKeyword || '').trim()
  if (owner) query.ownerKeyword = owner
  const prefix = (filters.tokenPrefix || '').trim()
  if (prefix) query.tokenPrefix = prefix
  return query
}

/** 是否有生效中的筛选（用于空态文案） */
export function hasShareFilters(filters: ShareFilters): boolean {
  return (
    filters.status === 0 ||
    filters.status === 1 ||
    (filters.ownerKeyword || '').trim() !== '' ||
    (filters.tokenPrefix || '').trim() !== ''
  )
}

export interface ShareEmptyText {
  main: string
  sub: string
}

/** 空态文案：接口不可用 / 加载失败 / 筛选无结果 / 真的没有数据，四种分开说 */
export function shareEmptyText(opts: {
  endpointMissing: boolean
  loadError: string
  filtered: boolean
}): ShareEmptyText {
  if (opts.endpointMissing) {
    return {
      main: '分享链接接口未上线',
      sub: 'GET /api/admin/shares 返回 404，通常是后端未部署到该版本；接口可用后本页会自动展示真实数据。'
    }
  }
  if (opts.loadError) {
    return { main: '分享链接加载失败', sub: `后端原始错误：${opts.loadError}` }
  }
  if (opts.filtered) {
    return { main: '没有匹配的分享链接', sub: '当前筛选条件没有结果，可调整状态 / 用户名关键词 / token 前缀后重试。' }
  }
  return { main: '暂无分享链接', sub: '当前还没有任何分享记录。' }
}

/** 撤销失败的提示：404 是「已不存在或已撤销」，其它错误原样展示后端 message */
export function revokeErrorText(status: number | undefined, backendMessage: string | undefined): string {
  if (status === 404) return '该链接已不存在或已撤销'
  return backendMessage && backendMessage.trim() ? backendMessage : '撤销失败，请稍后重试'
}
