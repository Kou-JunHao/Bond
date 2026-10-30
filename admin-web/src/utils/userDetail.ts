/**
 * 用户详情抽屉的纯函数（task-122）。
 *
 * 数据来源都用**已存在**的接口，不要求后端新增：
 *  - 空间：`GET /api/admin/workspaces?page&size` 只有分页参数，**没有 ownerKeyword**，
 *    所以只能按页拉取后在前端按 `ownerId` 过滤（页面最多扫 5 页 / 500 条，并在 UI 上注明）。
 *  - 审计：`GET /api/admin/audit-logs` 只支持按 `actorId`（操作人）过滤，**没有 targetId**；
 *    这里取最近 N 条后按「目标用户 = 该用户 或 操作人 = 该用户」做前端过滤，UI 如实标注该限制。
 */
import type { AdminShareItem, AuditLog, SnowflakeId, WorkspaceItem } from '../types'

/** 工作区列表接口单页上限（后端 LIMIT/OFFSET 无 clamp，这里按 100 作为扫描步长） */
export const WORKSPACE_SCAN_SIZE = 100
/** 最多扫描多少页工作区，避免用户量大的站点在抽屉里发太多请求 */
export const WORKSPACE_SCAN_MAX_PAGES = 5
/** 审计一次取回的条数（后端 size 上限 100） */
export const AUDIT_SCAN_SIZE = 100
/** 抽屉内「最近审计」展示条数 */
export const USER_DETAIL_AUDIT_LIMIT = 5

export interface UsageSummary {
  workspaces: number
  usedBytes: number
  maxBytes: number
  /** 有 maxSize 的空间数量；为 0 时说明后端没给配额（不应出现，防御用） */
  withQuota: number
}

/** 从工作区列表中挑出 ownerId 属于该用户的条目（雪花 ID 是字符串，不能用 Number 比较） */
export function pickOwnerWorkspaces(list: WorkspaceItem[], userId: SnowflakeId): WorkspaceItem[] {
  return (list || []).filter((w) => w && String(w.ownerId) === String(userId))
}

/** 汇总用量：usedSize / maxSize 都按数字累加，null/NaN 记 0 */
export function sumUsage(workspaces: WorkspaceItem[]): UsageSummary {
  const summary: UsageSummary = { workspaces: 0, usedBytes: 0, maxBytes: 0, withQuota: 0 }
  for (const w of workspaces || []) {
    summary.workspaces += 1
    const used = Number(w.usedSize)
    if (Number.isFinite(used) && used > 0) summary.usedBytes += used
    const max = Number(w.maxSize)
    if (Number.isFinite(max) && max > 0) {
      summary.maxBytes += max
      summary.withQuota += 1
    }
  }
  return summary
}

/** 用量百分比：配额未知时返回 null（UI 不画进度条） */
export function usagePercent(usedBytes: number, maxBytes: number): number | null {
  if (!Number.isFinite(maxBytes) || maxBytes <= 0) return null
  const percent = (usedBytes / maxBytes) * 100
  return Math.max(0, Math.min(100, percent))
}

/** 审计条目是否与该用户相关：目标用户 = 他，或操作人 = 他 */
export function isAuditRelated(log: AuditLog, userId: SnowflakeId): boolean {
  if (!log) return false
  const id = String(userId)
  return (log.targetId != null && String(log.targetId) === id) || (log.actorId != null && String(log.actorId) === id)
}

/** 从最近一批审计里过滤出该用户相关条目，保持后端顺序，截取前 limit 条 */
export function filterAuditsForUser(
  logs: AuditLog[],
  userId: SnowflakeId,
  limit = USER_DETAIL_AUDIT_LIMIT
): AuditLog[] {
  return (logs || []).filter((log) => isAuditRelated(log, userId)).slice(0, limit)
}

/** 审计区块的说明文案：扫描范围与后端过滤能力要如实写出来 */
export function auditScopeText(scanned: number, matched: number): string {
  return `后端不支持按用户过滤，这里取最近 ${scanned} 条审计、按「目标用户或操作人」匹配，命中 ${matched} 条（最多展示 ${USER_DETAIL_AUDIT_LIMIT} 条）`
}

/** 空间区块的说明文案（后端无 ownerKeyword，只能前端过滤） */
export function spaceScopeText(scannedPages: number, workspaces: number): string {
  const pageText = scannedPages > 1 ? `最近 ${scannedPages * WORKSPACE_SCAN_SIZE} 条工作区` : '最近一页工作区'
  return `后端工作区列表不支持按用户过滤，这里从${pageText}中按 ownerId 匹配到 ${workspaces} 个空间`
}

/** 空间条目的标题：个人空间给一个可识别的名字（后端列表只给 name/description） */
export function workspaceLabel(w: WorkspaceItem): string {
  const name = (w.name || '').trim()
  if (!name) return `工作区 ${String(w.id).slice(-6)}`
  return name
}

/** 分享条目是否仍有效（status 0=正常；过期另看 expiresAt） */
export function shareStateText(share: AdminShareItem): string {
  if (share.status === 1) return '已撤销'
  if (share.expiresAt != null && share.expiresAt <= Date.now()) return '已过期'
  return '正常'
}
