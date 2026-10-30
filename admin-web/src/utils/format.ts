import dayjs from 'dayjs'

/** 字节数转人类可读（1024 进制，保留 1~2 位小数） */
export function formatBytes(bytes?: number | null): string {
  if (bytes == null || Number.isNaN(bytes)) return '—'
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let value = bytes
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i += 1
  }
  const digits = value >= 100 || i === 0 ? 0 : value >= 10 ? 1 : 2
  return `${value.toFixed(digits)} ${units[i]}`
}

/** 数字千分位 */
export function formatNumber(value?: number | null): string {
  if (value == null || Number.isNaN(value)) return '—'
  return value.toLocaleString('zh-CN')
}

/**
 * 时间格式化：兼容「毫秒时间戳」与后端 LocalDateTime 字符串（无时区，按浏览器本地时间展示）。
 */
export function formatDateTime(value?: number | string | null, pattern = 'YYYY-MM-DD HH:mm'): string {
  if (value == null || value === '') return '—'
  if (typeof value === 'number') return dayjs(value).format(pattern)
  // 后端 updatedAt 形如 2026-10-22T01:22:44.37415（无时区）→ dayjs 当本地时间解析，语义一致
  return dayjs(value).format(pattern)
}

/** 相对时间：3 分钟前 / 2 小时前 / 3 天前 */
export function fromNow(value?: number | string | null): string {
  if (value == null || value === '') return '—'
  const ts = typeof value === 'number' ? value : dayjs(value).valueOf()
  const diff = Date.now() - ts
  if (Number.isNaN(diff)) return '—'
  // 未来时间不要显示「刚刚」：那是曾经把「过期时间」渲染成"刚刚"的根因，交给 untilNow 描述。
  if (diff < 0) return untilNow(value)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hours = Math.floor(min / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months} 个月前`
  return `${Math.floor(months / 12)} 年前`
}

/**
 * 未来时间的人类可读描述（用于「过期时间」「到期时间」这类列）：
 * 3 天后 / 12 小时后 / 已过期 2 天 / 今天到期。
 *
 * 注意：**不要**对未来的时间用 `fromNow()`——它只描述过去，未来的差值会被当成「刚刚」。
 */
export function untilNow(value?: number | string | null): string {
  if (value == null || value === '') return ''
  const ts = typeof value === 'number' ? value : dayjs(value).valueOf()
  if (Number.isNaN(ts)) return ''
  const diff = ts - Date.now()
  const abs = Math.abs(diff)
  const min = Math.floor(abs / 60000)
  const expired = diff < 0
  let amount: string
  if (min < 60) amount = min < 1 ? '不到 1 分钟' : `${min} 分钟`
  else if (min < 60 * 24) amount = `${Math.floor(min / 60)} 小时`
  else if (min < 60 * 24 * 30) amount = `${Math.floor(min / (60 * 24))} 天`
  else if (min < 60 * 24 * 365) amount = `${Math.floor(min / (60 * 24 * 30))} 个月`
  else amount = `${Math.floor(min / (60 * 24 * 365))} 年`
  return expired ? `已过期 ${amount}` : `${amount}后过期`
}

/** 邮箱脱敏：a***@b.com（保留首字符与域名） */
export function maskEmail(email?: string | null): string {
  if (!email) return ''
  const at = email.indexOf('@')
  if (at <= 0) return email
  const local = email.slice(0, at)
  const domain = email.slice(at)
  const head = local.slice(0, 1)
  const tail = local.length >= 4 ? local.slice(-1) : ''
  return `${head}***${tail}${domain}`
}

/** 百分比（0~100 整数） */
export function percent(used?: number | null, total?: number | null): number {
  if (!total || total <= 0) return 0
  return Math.min(100, Math.round(((used || 0) / total) * 100))
}

/** 操作人展示：管理员 #短 ID（完整 ID 由调用方放 tooltip/复制，不裸露 17 位数字） */
export function formatOperatorId(id?: string | null): string {
  if (!id) return '—'
  const s = String(id)
  return `管理员 #${s.length > 6 ? s.slice(-6) : s}`
}

/** 复制到剪贴板（带回退） */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 忽略，走回退
  }
  try {
    const el = document.createElement('textarea')
    el.value = text
    el.style.position = 'fixed'
    el.style.opacity = '0'
    document.body.appendChild(el)
    el.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(el)
    return ok
  } catch {
    return false
  }
}
