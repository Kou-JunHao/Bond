import type { AxiosError } from 'axios'

/** 后端统一响应体（成功/失败都是 { code, message, data }） */
export interface ApiErrorBody {
  code?: number
  message?: string
  data?: unknown
}

/** HTTP 状态码 → 面向用户的中文文案（兜底用，后端 message 永远优先） */
const STATUS_TEXT: Record<number, string> = {
  400: '请求参数有误，请检查后重试',
  401: '登录已失效，请重新登录',
  403: '无权限访问：该账号可能没有管理员权限',
  404: '接口或资源不存在',
  405: '请求方式不被支持',
  409: '数据冲突，请刷新后重试',
  422: '提交的数据未通过校验',
  429: '请求过于频繁，请稍后重试',
  500: '服务异常，请稍后重试',
  502: '网关异常，请稍后重试',
  503: '服务暂不可用，请稍后重试',
  504: '网关超时，请稍后重试'
}

/** 按状态码取中文兜底文案（没有匹配时对 4xx/5xx 给通用中文） */
export function statusText(status?: number): string {
  if (!status) return ''
  if (STATUS_TEXT[status]) return STATUS_TEXT[status]
  if (status >= 500) return '服务异常，请稍后重试'
  if (status >= 400) return '请求失败，请稍后重试'
  return ''
}

/**
 * axios 自己抛的英文串（如 `Request failed with status code 403`）不允许直接展示给用户。
 * 这里集中判定，避免英文原文漏到界面上。
 */
function isLowLevelNoise(msg: string): boolean {
  const text = msg.trim()
  return (
    /^Request failed with status code \d+$/i.test(text) ||
    /^timeout of \d+ms exceeded$/i.test(text) ||
    /^Network Error$/i.test(text) ||
    /^ERR_[A-Z_]+$/i.test(text) ||
    /status code \d+/i.test(text) && /request failed/i.test(text)
  )
}

/** 兼容 axios 错误、普通 Error、字符串等任意抛出物 */
interface ErrorLike {
  response?: { status?: number; data?: ApiErrorBody | string }
  message?: string
  code?: string
}

/**
 * 统一错误文案（页面/拦截器都走这里）：
 * 1) 后端 `{ message }` 原文优先（保留业务原因）；
 * 2) 否则按 HTTP 状态码给中文（401/403/404/409/429/5xx）；
 * 3) 再否则处理超时/断网；
 * 4) 最后才用 fallback —— 任何情况下都不回显 axios 英文原文。
 */
export function formatApiError(err: unknown, fallback = '操作失败，请稍后重试'): string {
  const e = err as ErrorLike | undefined

  const data = e?.response?.data
  if (data && typeof data === 'object' && typeof data.message === 'string' && data.message.trim()) {
    const msg = data.message.trim()
    if (!isLowLevelNoise(msg)) return msg
  }
  if (typeof data === 'string' && data.trim() && !isLowLevelNoise(data)) return data.trim()

  const status = e?.response?.status
  if (status) return statusText(status) || fallback

  const raw = e?.message || ''
  if (e?.code === 'ECONNABORTED' || /timeout of \d+ms exceeded/i.test(raw)) {
    return '请求超时，请稍后重试'
  }
  if (/Network Error/i.test(raw)) return '网络异常，请检查网络后重试'
  if (raw && !isLowLevelNoise(raw)) return raw
  return fallback
}

/** 是否为 403（无权限）——用于「登录后校验管理员身份」与全局提示 */
export function isForbidden(err: unknown): boolean {
  return (err as AxiosError)?.response?.status === 403
}

/** 后端 404/405/501 通常意味着「路由还没上线」，与业务错误区分开 */
export function isEndpointMissing(err: unknown): boolean {
  const status = (err as AxiosError)?.response?.status
  return status === 404 || status === 405 || status === 501
}

/**
 * 兼容旧调用点：等价于 formatApiError。
 * 页面里不要再手写 `err.message`，否则 axios 英文原文会漏到界面上。
 */
export function errorText(err: unknown, fallback = '操作失败'): string {
  return formatApiError(err, fallback)
}

/** 从 axios 错误里取 HTTP 状态码（没有响应时为 undefined） */
export function errorStatus(err: unknown): number | undefined {
  return (err as AxiosError)?.response?.status
}

export const ENDPOINT_MISSING_TEXT = '接口未上线（后端待部署），请稍后再试'
