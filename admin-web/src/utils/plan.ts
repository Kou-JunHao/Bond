/**
 * 用户档位相关的纯展示/计算逻辑（task-104）。
 *
 * 约定：档位名称一律以后端返回的 `planName` 为准（生产已改为中文），
 * 本文件不做 code → 中文映射；只有后端暂未返回名称时才回退展示 code。
 */

export interface PlanUserLike {
  planCode?: string | null
  planName?: string | null
  planExpiresAt?: number | null
}

export interface PlanOption {
  code: string
  label: string
}

export type PlanTerm = 'keep' | 1 | 3 | 6 | 12 | 'custom'

export const PLAN_TERM_OPTIONS: Array<{ value: PlanTerm; label: string }> = [
  { value: 'keep', label: '保留当前到期时间' },
  { value: 1, label: '1 个月' },
  { value: 3, label: '3 个月' },
  { value: 6, label: '6 个月' },
  { value: 12, label: '12 个月' },
  { value: 'custom', label: '指定到期时间' }
]

/** 表格/弹窗里展示的档位名：优先后端 planName，其次 planCode，最后 — */
export function displayPlanName(name?: string | null, code?: string | null): string {
  const n = (name || '').trim()
  if (n) return n
  const c = (code || '').trim()
  return c || '—'
}

export function isPlanExpired(expiresAt?: number | null, now: number = Date.now()): boolean {
  return typeof expiresAt === 'number' && expiresAt > 0 && expiresAt < now
}

/** 档位 tag 颜色：过期红、团队紫、会员蓝、其余默认（仅视觉，不涉及名称映射） */
export function planTagColor(code?: string | null, expiresAt?: number | null): string {
  if (isPlanExpired(expiresAt)) return 'red'
  const c = (code || '').toLowerCase()
  if (c === 'team') return 'purple'
  if (c === 'plus') return 'blue'
  return 'default'
}

/** 到期时间文案：null/0 视为永久；否则 yyyy-MM-dd HH:mm */
export function planExpiryText(expiresAt?: number | null): string {
  if (!expiresAt) return '永久'
  const d = new Date(expiresAt)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 从用户列表里收集档位选项：code 去重，name 取后端 planName。
 * 保证 free/plus/team 三个基础档位在前（缺名称时回退 code），其余按出现顺序追加。
 */
export function collectPlanOptions(users: PlanUserLike[]): PlanOption[] {
  const names = new Map<string, string>()
  for (const u of users || []) {
    const code = (u.planCode || '').trim()
    if (!code) continue
    const name = (u.planName || '').trim()
    if (!names.has(code) || (!names.get(code) && name)) names.set(code, name)
  }
  const order = ['free', 'plus', 'team']
  const codes = [
    ...order,
    ...[...names.keys()].filter((c) => !order.includes(c))
  ]
  const seen = new Set<string>()
  const out: PlanOption[] = []
  for (const code of codes) {
    if (seen.has(code)) continue
    seen.add(code)
    out.push({ code, label: names.get(code) || code })
  }
  return out
}

/** 本地时间转带时区偏移的 ISO 8601（后端 OffsetDateTime 可直接解析） */
export function toOffsetIso(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  const offsetMin = -date.getTimezoneOffset()
  const sign = offsetMin >= 0 ? '+' : '-'
  const abs = Math.abs(offsetMin)
  return (
    `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}` +
    `T${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}` +
    `${sign}${p(Math.floor(abs / 60))}:${p(abs % 60)}`
  )
}

/** 预计到期文案（提交前二次确认用）：按当前时间 + N 个月估算 */
export function monthsLaterText(months: number, from: number = Date.now()): string {
  const d = new Date(from)
  d.setMonth(d.getMonth() + months)
  return planExpiryText(d.getTime())
}

/** 调整档位的二次确认文案（敏感操作，写清 from→to 与到期时间） */
export function planConfirmText(
  username: string,
  fromLabel: string,
  toLabel: string,
  expiryText: string
): string {
  return `将把「${username}」的档位从「${fromLabel}」改为「${toLabel}」，到期时间：${expiryText}。该操作会立即生效并记录审计日志。`
}
