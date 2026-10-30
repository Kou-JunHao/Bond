import type { MailDeliveryLog } from '../types'

/**
 * 投递日志的纯逻辑（与渲染解耦，便于用 node:test 直接覆盖）。
 * 这里不引用 axios / Vue，保证测试不需要额外依赖、不用挂载组件。
 */

/** 失败率展示：接口给的是 0–100 的百分比；total=0 视为无数据 0.00% */
export function formatFailureRate(total: number | undefined, failureRate: number | undefined): string {
  const t = total || 0
  if (t === 0) return '0.00%'
  if (typeof failureRate === 'number' && Number.isFinite(failureRate)) {
    return `${failureRate.toFixed(2)}%`
  }
  return '—'
}

/** 失败率颜色：能算出失败数且 > 0 用红色，否则绿色；缺字段时退回 failed 判断 */
export function failureRateClass(
  total: number | undefined,
  failed: number | undefined,
  failureRate: number | undefined
): 'ok-text' | 'fail-text' {
  const t = total || 0
  if (t === 0) return 'ok-text'
  if (typeof failureRate === 'number' && Number.isFinite(failureRate)) {
    return failureRate > 0 ? 'fail-text' : 'ok-text'
  }
  return (failed || 0) > 0 ? 'fail-text' : 'ok-text'
}

/** placeholdersLeft > 0 的行（“只看占位符残留”筛选的内核） */
export function filterPlaceholderRows(rows: MailDeliveryLog[]): MailDeliveryLog[] {
  return rows.filter((row) => (row.placeholdersLeft || 0) > 0)
}

/** 客户端分页切片（占位符筛选模式下用） */
export function slicePage<T>(rows: T[], page: number, size: number): T[] {
  const safePage = Math.max(1, Math.floor(page || 1))
  const safeSize = Math.max(1, Math.floor(size || 1))
  const start = (safePage - 1) * safeSize
  return rows.slice(start, start + safeSize)
}

/** 客户端分页总页数（占位符筛选模式下用） */
export function pageCount(total: number, size: number): number {
  const safeSize = Math.max(1, Math.floor(size || 1))
  return Math.ceil(Math.max(0, total) / safeSize)
}

/** 用途中文名映射，查不到时回退原始 code，再回退 — */
export function purposeLabelOf(map: Record<string, string> | undefined, purpose: string | undefined): string {
  if (!purpose) return '—'
  return map?.[purpose] || purpose
}

/** 失败行才可展开（成功行没有 failureReason 可看） */
export function rowExpandable(row: { status?: string }): boolean {
  return row.status === 'failed'
}

/** 失败行整行标红 */
export function rowClassNameOf(row: { status?: string }): string {
  return row.status === 'failed' ? 'row-failed' : ''
}

/** 变量 chip 折叠：多于 4 个时保留 4 个 + 余数 */
export const VARIABLE_CHIP_LIMIT = 4

export function splitVariableKeys(keys: string[] | undefined): { shown: string[]; more: number } {
  const list = keys || []
  return {
    shown: list.slice(0, VARIABLE_CHIP_LIMIT),
    more: Math.max(0, list.length - VARIABLE_CHIP_LIMIT)
  }
}
