import type { LoginLogStats, LoginOutcome } from '../types'

/** 登录结果（与后端 AdminLoginGuardService.outcome 取值一致） */
export const LOGIN_OUTCOME_OPTIONS: Array<{ value: LoginOutcome; label: string; color: string }> = [
  { value: 'success', label: '成功', color: 'success' },
  { value: 'bad_credentials', label: '密码错误', color: 'warning' },
  { value: 'user_disabled', label: '账号禁用', color: 'default' },
  { value: 'blocked', label: '已封禁', color: 'error' },
  { value: 'captcha_required', label: '需验证码', color: 'processing' },
  { value: 'error', label: '异常', color: 'default' }
]

const OUTCOME_MAP = new Map(LOGIN_OUTCOME_OPTIONS.map((o) => [o.value as string, o]))

/** 结果 → 中文标签（未知取值原样展示，绝不编造归类） */
export function outcomeLabel(outcome?: string | null): string {
  if (!outcome) {
    return '未知'
  }
  return OUTCOME_MAP.get(outcome)?.label ?? outcome
}

/** 结果 → antd tag color */
export function outcomeTagColor(outcome?: string | null): string {
  if (!outcome) {
    return 'default'
  }
  return OUTCOME_MAP.get(outcome)?.color ?? 'default'
}

/** 失败率文案：总数 0 时返回「—」，避免 0/0 显示成 0% 或 NaN */
export function failureRateText(total: number, success: number): string {
  if (!Number.isFinite(total) || total <= 0) {
    return '—'
  }
  const safeSuccess = Number.isFinite(success) ? success : 0
  const failed = Math.max(0, total - safeSuccess)
  return `${((failed / total) * 100).toFixed(2)}%`
}

/** 长文本截断（User-Agent 等），空值显示「—」 */
export function truncateText(text?: string | null, max = 48): string {
  if (!text) {
    return '—'
  }
  const value = text.trim()
  if (!value) {
    return '—'
  }
  return value.length > max ? `${value.slice(0, max)}…` : value
}

/** 剩余秒数 → 可读倒计时（0 或负数 → 已到期） */
export function countdownLabel(seconds?: number | null): string {
  const value = Math.max(0, Math.floor(Number(seconds) || 0))
  if (value <= 0) {
    return '已到期'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  if (value >= 86400) {
    const days = Math.floor(value / 86400)
    const hours = Math.floor((value % 86400) / 3600)
    return `${days} 天 ${hours} 小时`
  }
  if (value >= 3600) {
    const hours = Math.floor(value / 3600)
    const minutes = Math.floor((value % 3600) / 60)
    return `${hours} 小时 ${pad(minutes)} 分`
  }
  const minutes = Math.floor(value / 60)
  const secs = value % 60
  return `${minutes} 分 ${pad(secs)} 秒`
}

/** 「解除封禁」二次确认文案：说明立即生效 + 对象名 */
export function liftConfirmText(block: {
  scope: 'ip' | 'user'
  value: string
  remainingSeconds?: number | null
}): string {
  const target = block.scope === 'ip' ? `IP ${block.value}` : `账号 ${block.value}`
  const remaining = block.remainingSeconds && block.remainingSeconds > 0
    ? `（原剩余 ${countdownLabel(block.remainingSeconds)}）`
    : ''
  return `解除后立即生效：${target} 将立刻恢复登录尝试${remaining}。确定解除？`
}

export type TimeRangeKey = 'today' | '7d' | '30d' | 'custom'

export const TIME_RANGE_OPTIONS: Array<{ value: TimeRangeKey; label: string }> = [
  { value: 'today', label: '今天' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'custom', label: '自定义' }
]

function formatLocalDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 快捷时间范围 → 后端 from/to（yyyy-MM-dd，后端按 +08:00 解析） */
export function rangeToFromTo(
  range: TimeRangeKey,
  customFrom?: string | null,
  customTo?: string | null,
  now: Date = new Date()
): { from?: string; to?: string } {
  if (range === 'custom') {
    return {
      from: customFrom ? customFrom.trim() : undefined,
      to: customTo ? customTo.trim() : undefined
    }
  }
  const today = formatLocalDate(now)
  if (range === 'today') {
    return { from: today, to: today }
  }
  const days = range === '7d' ? 6 : 29
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days)
  return { from: formatLocalDate(start), to: today }
}

/** 从 URL query 还原筛选项（刷新可复现） */
export interface LoginFilters {
  ip: string
  username: string
  outcome?: LoginOutcome
  range: TimeRangeKey
  from?: string
  to?: string
}

export function queryToFilters(query: Record<string, unknown>): LoginFilters {
  const pick = (key: string) => {
    const value = query[key]
    if (Array.isArray(value)) {
      return value[0] ? String(value[0]) : ''
    }
    return value == null ? '' : String(value)
  }
  const rangeRaw = pick('range')
  const outcome = pick('outcome')
  const base = {
    ip: pick('ip'),
    username: pick('account') || pick('username'),
    outcome: (outcome || undefined) as LoginOutcome | undefined
  }
  if (rangeRaw === 'today' || rangeRaw === '7d' || rangeRaw === '30d') {
    return { ...base, range: rangeRaw }
  }
  const from = pick('from') || undefined
  const to = pick('to') || undefined
  if (from || to) {
    const today = formatLocalDate(new Date())
    return {
      ...base,
      range: from === today && to === today ? 'today' : 'custom',
      from,
      to
    }
  }
  return { ...base, range: '7d' }
}

/** 筛选项 → URL query（只放有值的键，避免 URL 噪音） */
export function filtersToQuery(filters: LoginFilters): Record<string, string> {
  const query: Record<string, string> = {}
  if (filters.ip.trim()) query.ip = filters.ip.trim()
  if (filters.username.trim()) query.account = filters.username.trim()
  if (filters.outcome) query.outcome = filters.outcome
  if (filters.range === 'custom') {
    if (filters.from) query.from = filters.from
    if (filters.to) query.to = filters.to
  } else {
    query.range = filters.range
  }
  return query
}

/** 折线图原始桶：接口 daily[] 只有「有登录记录」的日期 */
export interface DailyBucket {
  date: string
  success: number
  failed: number
}

/**
 * 把接口返回的稀疏 daily[] 补齐成「今天往前 days 天」的连续序列。
 * 缺失日期按 0 填充（0 表示当天没有任何登录记录，不是编造数据），
 * 这样 7/30/90 三档的 X 轴间距才是真实的。
 */
export function buildDailySeries(
  daily: LoginLogStats['daily'] | DailyBucket[] | undefined,
  days: number,
  today: Date = new Date()
): DailyBucket[] {
  const span = Math.min(90, Math.max(1, Math.floor(Number(days) || 7)))
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const byDate = new Map<string, DailyBucket>()
  for (const row of Array.isArray(daily) ? daily : []) {
    if (!row || typeof row.date !== 'string' || !row.date) continue
    const key = row.date.slice(0, 10)
    const current = byDate.get(key) ?? { date: key, success: 0, failed: 0 }
    current.success += Number(row.success) || 0
    current.failed += Number(row.failed) || 0
    byDate.set(key, current)
  }
  const series: DailyBucket[] = []
  for (let i = span - 1; i >= 0; i--) {
    const d = new Date(end.getFullYear(), end.getMonth(), end.getDate() - i)
    const key = formatLocalDate(d)
    series.push(byDate.get(key) ?? { date: key, success: 0, failed: 0 })
  }
  return series
}

/** 向上取整成「好看」的 Y 轴最大值（保证至少 5 档刻度） */
export function niceMaxValue(value: number, ticks = 5): number {
  const v = Math.max(1, Math.ceil(Number(value) || 0))
  const steps = Math.max(2, Math.floor(ticks) - 1)
  const rawStep = v / steps
  const magnitude = 10 ** Math.floor(Math.log10(Math.max(rawStep, 1)))
  const step = Math.max(1, Math.ceil(rawStep / magnitude) * magnitude)
  return step * steps
}

/** 折线图几何：把连续序列渲染成 SVG 坐标（无图表库，纯计算便于单测） */
export interface ChartPoint {
  x: number
  y: number
}

export interface ChartTick {
  y: number
  value: number
}

export interface DailyChartGeometry {
  successPoints: ChartPoint[]
  failedPoints: ChartPoint[]
  maxValue: number
  yTicks: ChartTick[]
  labels: Array<{ x: number; y: number; text: string }>
}

export function dailyChartGeometry(
  series: DailyBucket[] | undefined,
  width = 640,
  height = 180,
  padding = 24,
  ticks = 5
): DailyChartGeometry {
  const rows = Array.isArray(series) ? series : []
  const chartWidth = Math.max(1, width - padding * 2)
  const chartHeight = Math.max(1, height - padding * 2)
  const peak = Math.max(1, ...rows.map((r) => Math.max(Number(r.success) || 0, Number(r.failed) || 0)))
  const maxValue = niceMaxValue(peak, ticks)
  const step = rows.length > 1 ? chartWidth / (rows.length - 1) : 0
  const xOf = (i: number) => (rows.length > 1 ? padding + step * i : padding + chartWidth / 2)
  const yOf = (value: number) => padding + (1 - (Number(value) || 0) / maxValue) * chartHeight
  const successPoints = rows.map((r, i) => ({ x: xOf(i), y: yOf(r.success) }))
  const failedPoints = rows.map((r, i) => ({ x: xOf(i), y: yOf(r.failed) }))
  const tickCount = Math.max(2, Math.floor(ticks))
  const yTicks: ChartTick[] = Array.from({ length: tickCount }, (_, i) => {
    const value = Math.round((maxValue / (tickCount - 1)) * (tickCount - 1 - i))
    return { y: yOf(value), value }
  })
  const maxLabels = 6
  const labelStep = rows.length > maxLabels ? Math.ceil((rows.length - 1) / (maxLabels - 1)) : 1
  const labelIndexes: number[] = []
  for (let i = 0; i < rows.length; i += labelStep) labelIndexes.push(i)
  if (rows.length && labelIndexes[labelIndexes.length - 1] !== rows.length - 1) labelIndexes.push(rows.length - 1)
  const labels = labelIndexes.map((i) => ({
    x: xOf(i),
    y: height - 4,
    text: rows[i]?.date ?? ''
  }))
  return { successPoints, failedPoints, maxValue, yTicks, labels }
}

/** SVG polyline points 属性 */
export function pointsToPolyline(points: ChartPoint[]): string {
  return points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
}
