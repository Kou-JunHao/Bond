import http from './admin'
import type { AdminShareItem, ApiResult, PageResult } from '../types'

/**
 * 分享链接治理 API（task-91，后端由 task-89 提供）。
 *
 * 真实契约（2026-10-24 生产实测，Lead 逐个核过）：
 *  - GET    /api/admin/shares?page=&size=&status=&ownerKeyword=&tokenPrefix=
 *           → {code,message,data:{items,total,page,size}}
 *           ownerKeyword 为 ILIKE 模糊（忽略大小写）；tokenPrefix 为前缀匹配；size <= 100
 *  - DELETE /api/admin/shares/{id} → 204（已撤销或不存在 → 404）
 *
 * 不返回签名 URL、不返回文件内容；token 已收敛，页面只展示前缀。
 */

export interface ShareListParams {
  page: number
  size: number
  /** 0=正常，1=已撤销 */
  status?: 0 | 1
  /** 用户名关键词（后端 ILIKE 模糊匹配） */
  ownerKeyword?: string
  /** token 前缀（后端前缀匹配） */
  tokenPrefix?: string
}

const asResult = <T>(p: Promise<unknown>) => p as unknown as Promise<ApiResult<T>>

export const sharesApi = {
  /** 分页列表：total 随筛选条件变化 */
  list: (params: ShareListParams) =>
    asResult<PageResult<AdminShareItem>>(http.get('/admin/shares', { params })),

  /** 强制撤销：成功 204；已撤销/不存在 404（业务错误码由调用方原样展示） */
  revoke: (id: string) => asResult<null>(http.delete(`/admin/shares/${encodeURIComponent(id)}`))
}

export default sharesApi
