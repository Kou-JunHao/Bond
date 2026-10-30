import { bytesToParts, bytesText, bytesFromParts, BYTE_UNITS, type ByteUnit } from './settings.ts'
import type { AdminPlan, PlanLimitKey, PlanLimits, PlanLimitsPatch } from '../types'

/** limits 键的分组与展示元数据（12 个键全部在这里，顺序即页面顺序） */
export interface PlanLimitMeta {
  key: PlanLimitKey
  label: string
  group: string
  kind: 'bytes' | 'int'
  /** 最小值（后端校验的下限，前端提前拦一下明显非法值，最终以后端 400 为准） */
  min: number
  /** 最大值（仅作提示与文档用途；**前端不拦截上限**，越界以后端 400 为准） */
  max?: number
  /** 值为 0 时的业务含义（null 表示 0 无特殊含义） */
  zeroText: string | null
  hint: string
}

export const PLAN_LIMIT_META: PlanLimitMeta[] = [
  { key: 'storage_bytes', label: '存储空间', group: '存储与文件', kind: 'bytes', min: 1, zeroText: null,
    hint: '个人空间 / 每个团队工作区分别按此额度分配' },
  { key: 'max_file_size', label: '单文件上限', group: '存储与文件', kind: 'bytes', min: 1, zeroText: null,
    hint: '单个文件大小上限' },
  { key: 'relay_bytes_month', label: '每月高速流量', group: '流量与速度', kind: 'bytes', min: 1, zeroText: null,
    hint: '只统计下行流量' },
  { key: 'relay_down_bytes_per_sec', label: '下行限速', group: '流量与速度', kind: 'bytes', min: 0, zeroText: '不限速',
    hint: '0 = 不限速' },
  { key: 'max_workspaces', label: '团队工作区数', group: '工作区与传输', kind: 'int', min: 1, zeroText: null,
    hint: '可创建的团队工作区数量' },
  { key: 'max_workspace_members', label: '工作区成员上限', group: '工作区与传输', kind: 'int', min: 1, zeroText: null,
    hint: '每个团队工作区的成员上限' },
  { key: 'concurrent_transfers', label: '并发传输数', group: '工作区与传输', kind: 'int', min: 1, zeroText: null,
    hint: '单账号同时进行的传输数量' },
  { key: 'grace_days', label: '到期宽限天数', group: '档位宽限', kind: 'int', min: 0, max: 365, zeroText: '无宽限',
    hint: '档位到期后的宽限天数（0–365）' },
  { key: 'share_expires_days', label: '分享有效期', group: '分享与回收', kind: 'int', min: 0, zeroText: '永久有效',
    hint: '分享链接有效期天数' },
  { key: 'share_max_downloads', label: '分享下载次数', group: '分享与回收', kind: 'int', min: 0, zeroText: '不限次数',
    hint: '分享链接默认下载次数上限' },
  { key: 'share_max_links', label: '活跃分享数', group: '分享与回收', kind: 'int', min: 0, zeroText: '不限',
    hint: '单用户活跃分享链接数上限' },
  { key: 'trash_retention_days', label: '回收站保留期', group: '分享与回收', kind: 'int', min: 0, max: 3650,
    zeroText: '不限期保留', hint: '删除文件在回收站的保留天数（0–3650）' }
]

export const PLAN_LIMIT_KEYS: PlanLimitKey[] = PLAN_LIMIT_META.map((meta) => meta.key)

/** 分组顺序（按 PLAN_LIMIT_META 首次出现的顺序） */
export const PLAN_LIMIT_GROUPS: { group: string; metas: PlanLimitMeta[] }[] = (() => {
  const groups: { group: string; metas: PlanLimitMeta[] }[] = []
  for (const meta of PLAN_LIMIT_META) {
    let bucket = groups.find((item) => item.group === meta.group)
    if (!bucket) {
      bucket = { group: meta.group, metas: [] }
      groups.push(bucket)
    }
    bucket.metas.push(meta)
  }
  return groups
})()

export function limitMeta(key: string): PlanLimitMeta | undefined {
  return PLAN_LIMIT_META.find((meta) => meta.key === key)
}

export function isByteLimitKey(key: string): boolean {
  return limitMeta(key)?.kind === 'bytes'
}

/** 价格展示：0 → 免费；3000 → ¥30 / 月 */
export function formatPlanPrice(priceCents: number | null | undefined, period?: string | null): string {
  if (priceCents === null || priceCents === undefined || !Number.isFinite(priceCents)) return '—'
  if (priceCents === 0) return '免费'
  const yuan = priceCents / 100
  const amount = Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2).replace(/0$/, '')
  const unit = period === 'year' ? '年' : '月'
  return `¥${amount} / ${unit}`
}

/** 启用 / 停用徽标 */
export function planActiveTag(isActive: boolean | null | undefined): { text: string; color: string } {
  return isActive === false ? { text: '停用', color: 'default' } : { text: '启用', color: 'green' }
}

export interface LimitDisplay {
  text: string
  rawText: string
}

/** 展示值：字节类 → 5 GB + 原始字节；0 有语义的整数 → 中文含义 + 原始 0 */
export function formatLimitValue(key: string, value: unknown): LimitDisplay {
  const meta = limitMeta(key)
  if (value === null || value === undefined) return { text: meta?.zeroText ?? '—', rawText: '' }
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric)) return { text: String(value), rawText: '' }
  if (meta?.kind === 'bytes') {
    return { text: bytesText(numeric), rawText: String(numeric) }
  }
  if (numeric === 0 && meta?.zeroText) {
    return { text: meta.zeroText, rawText: '0' }
  }
  return { text: String(numeric), rawText: '' }
}

/** 行内编辑的预校验（前端只拦「整数 / 非负 / 安全整数」，范围上限以后端 400 为准） */
export function validateLimitValue(key: string, value: number | null | undefined): string | null {
  const meta = limitMeta(key)
  if (!meta) return `未知的 limits 键：${key}`
  if (value === null || value === undefined || !Number.isFinite(value) || !Number.isInteger(value)) {
    return '请输入整数'
  }
  if (value < meta.min) {
    return meta.kind === 'bytes' ? '字节数必须大于 0' : `不能小于 ${meta.min}`
  }
  if (!Number.isSafeInteger(value)) {
    return '数值过大（超出前端安全整数范围）'
  }
  return null
}

/** 「数值 + 单位」的字节输入 → 字节数；null 表示非法 */
export function byteLimitFromParts(value: number | null | undefined, unit: ByteUnit): number | null {
  const raw = bytesFromParts(value, unit)
  if (raw === null) return null
  const numeric = Number(raw)
  return Number.isSafeInteger(numeric) ? numeric : null
}

/** 字节数 → 行内编辑器的「数值 + 单位」 */
export function byteLimitParts(value: unknown): { value: number; unit: ByteUnit } {
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric) || !Number.isInteger(numeric) || numeric < 0) return { value: 0, unit: 'GB' }
  return bytesToParts(numeric)
}

export function byteUnitOptions(): { label: string; value: ByteUnit }[] {
  return BYTE_UNITS.map((unit) => ({ label: unit, value: unit }))
}

/**
 * diff：只返回`草稿里与基线不同`的键；值相同的键不算改动。
 * 草稿值为 null 表示删除该键（后端支持）。
 */
export function buildPlanPatch(
  baseline: PlanLimits,
  draft: Record<string, number | null>
): PlanLimitsPatch {
  const patch: PlanLimitsPatch = {}
  for (const [key, value] of Object.entries(draft)) {
    const before = baseline[key as PlanLimitKey]
    if (value === null) {
      if (before !== undefined) patch[key] = null
      continue
    }
    if (typeof before === 'number' && before === value) continue
    patch[key] = value
  }
  return patch
}

/** 卡片头部「已改动 N 项」 */
export function countDraftChanges(draft: Record<string, number | null> | undefined): number {
  return draft ? Object.keys(draft).length : 0
}

/** 校验整张卡片的草稿，返回第一个错误 */
export function validatePlanDraft(
  baseline: PlanLimits,
  draft: Record<string, number | null>
): string | null {
  for (const [key, value] of Object.entries(draft)) {
    const error = validateLimitValue(key, value)
    if (error) return `${limitMeta(key)?.label ?? key}：${error}`
    if (value !== null && value === baseline[key as PlanLimitKey]) continue
  }
  return null
}

/** 计划名兜底：后端 name 为空时用 code */
export function planTitle(plan: Pick<AdminPlan, 'code' | 'name'>): string {
  return plan.name?.trim() || plan.code
}
