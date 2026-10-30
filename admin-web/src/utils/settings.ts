/** 系统配置值的类型判定、展示与前端校验（纯函数，便于单测） */

export type ConfigControl = 'boolean' | 'integer' | 'number' | 'json' | 'text'

/** 后端 config_type 归一化（大小写/空白不敏感） */
export function normalizeConfigType(type: unknown): string {
  return String(type ?? '').trim().toLowerCase()
}

/** 值是否超长（表格里需要截断 + Tooltip） */
export function isLongConfigValue(value: unknown, max = 120): boolean {
  const text = String(value ?? '')
  return text.length > max || text.includes('\n')
}

/** 表格单元格预览：折叠换行、超长截断 */
export function configValuePreview(value: unknown, max = 120): string {
  const text = String(value ?? '').replace(/\r?\n/g, ' ')
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** 根据 config_type 选择编辑控件 */
export function configControl(type: unknown): ConfigControl {
  const t = normalizeConfigType(type)
  if (t === 'boolean' || t === 'bool') return 'boolean'
  if (t === 'int' || t === 'integer' || t === 'long' || t === 'bigint') return 'integer'
  if (t === 'double' || t === 'float' || t === 'decimal' || t === 'number') return 'number'
  if (t === 'json') return 'json'
  return 'text'
}

/** 控件对应的类型说明（给用户看的） */
export function configControlHint(type: unknown): string {
  switch (configControl(type)) {
    case 'boolean':
      return '布尔值：true / false'
    case 'integer':
      return '整数：例如 1024'
    case 'number':
      return '小数：例如 1.5'
    case 'json':
      return 'JSON：保存前会校验格式'
    default:
      return '文本'
  }
}

export function configTypeLabel(type: unknown): string {
  const t = normalizeConfigType(type)
  if (!t) return 'string'
  if (t === 'bool') return 'boolean'
  if (t === 'bigint') return 'long'
  return t
}

/* ───────────────────────── 字节类配置（task-108） ───────────────────────── */

/** 字节单位（1024 进制） */
export const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const
export type ByteUnit = (typeof BYTE_UNITS)[number]

export interface ByteConfigLike {
  configKey?: string | null
  description?: string | null
  configType?: string | null
}

/** 反例：时长/数量类键不是字节 */
const NON_BYTE_KEY_SUFFIX = /(_seconds|_ms|_hours|_days|_count|_per_minute|_minutes)$/
/** 正例：键名命中这些后缀/片段即视为字节容量 */
const BYTE_KEY_PATTERN = /(_bytes$|_size$|_limit$|chunk_size)/

/**
 * 是否为「字节类」配置（判定集中在这一个函数里）：
 * - configType 必须是 int / long；
 * - 且描述含「字节」/“bytes”，或键名命中 `_bytes$|_size$|_limit$|chunk_size`；
 * - 且键名不是 `*_seconds/_ms/_hours/_days/_count/_per_minute/_minutes` 这类时长/计数。
 */
export function isByteConfig(record: ByteConfigLike | null | undefined): boolean {
  if (!record) return false
  const type = normalizeConfigType(record.configType)
  if (type !== 'int' && type !== 'long') return false
  const key = String(record.configKey ?? '').trim().toLowerCase()
  if (!key || NON_BYTE_KEY_SUFFIX.test(key)) return false
  const description = String(record.description ?? '')
  const byDescription = description.includes('字节') || /bytes?\b/i.test(description)
  return byDescription || BYTE_KEY_PATTERN.test(key)
}

export function byteUnitFactor(unit: ByteUnit): number {
  const idx = BYTE_UNITS.indexOf(unit)
  return idx < 0 ? 1 : Math.pow(1024, idx)
}

/** 解析原始字节字符串（非负整数，超出 2^53-1 返回 null） */
export function parseByteCount(raw: unknown): number | null {
  const text = String(raw ?? '').trim()
  if (!/^\d+$/.test(text)) return null
  const n = Number(text)
  return Number.isSafeInteger(n) ? n : null
}

/**
 * 字节 → 「数值 + 最合适单位」：只选能被 1024 整除的更高单位，
 * 否则保持 B 以保留精度（与 task-108 要求一致）。
 */
export function bytesToParts(bytes: number): { value: number; unit: ByteUnit } {
  if (!Number.isFinite(bytes) || bytes < 0 || !Number.isInteger(bytes)) return { value: 0, unit: 'B' }
  let index = 0
  let value = bytes
  while (index < BYTE_UNITS.length - 1 && value !== 0 && value % 1024 === 0) {
    value /= 1024
    index += 1
  }
  return { value, unit: BYTE_UNITS[index] }
}

/** 字节 → 人类可读文本，例如 5242880 → '5 MB' */
export function bytesText(bytes: number): string {
  const parts = bytesToParts(bytes)
  return `${parts.value} ${parts.unit}`
}

/** 表格/弹窗展示：人类可读 + 原始字节（解析失败时原样展示） */
export function byteConfigDisplay(raw: unknown): { text: string; rawText: string } {
  const text = String(raw ?? '').trim()
  if (!text) return { text: '—', rawText: '' }
  const n = parseByteCount(text)
  if (n === null) return { text, rawText: '' }
  return { text: bytesText(n), rawText: String(n) }
}

/**
 * 「数值 + 单位」→ 写库用的整数字符串。
 * 返回 null 表示：非整数 / 负数 / 超出前端安全整数范围。
 */
export function bytesFromParts(value: number | null | undefined, unit: ByteUnit): string | null {
  if (value === null || value === undefined || !Number.isInteger(value) || value < 0) return null
  const bytes = value * byteUnitFactor(unit)
  if (!Number.isSafeInteger(bytes)) return null
  return String(bytes)
}

/**
 * 「原始值」模式：直接输入字节数，必须是纯数字且不超安全整数范围。
 * 返回 null 表示非法。
 */
export function normalizeRawByteCount(raw: unknown): string | null {
  const text = String(raw ?? '').trim()
  if (!/^\d+$/.test(text)) return null
  const n = Number(text)
  if (!Number.isSafeInteger(n)) return null
  return String(n)
}

/**
 * 前端校验（以后端 400 为准）。返回 null 表示通过，否则返回中文错误提示。
 */
export function validateConfigValue(type: unknown, raw: unknown): string | null {
  const value = String(raw ?? '')
  switch (configControl(type)) {
    case 'boolean': {
      const v = value.trim().toLowerCase()
      if (v !== 'true' && v !== 'false') return '布尔值只能是 true 或 false'
      return null
    }
    case 'integer': {
      const v = value.trim()
      if (!/^[+-]?\d+$/.test(v)) return '整数必须是数字（不能带小数点或字母）'
      const n = Number(v)
      if (!Number.isFinite(n)) return '数字超出可表示范围'
      if (normalizeConfigType(type) === 'int') {
        if (n > 2147483647 || n < -2147483648) return 'int 取值范围为 -2147483648 ~ 2147483647'
      } else if (!Number.isSafeInteger(n)) {
        return '该整数超出前端安全范围（|n| > 2^53-1），请确认后端 Long 能接受'
      }
      return null
    }
    case 'number': {
      const v = value.trim()
      if (v === '') return '请输入数字'
      if (!/^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/.test(v)) return '必须是合法数字'
      if (!Number.isFinite(Number(v))) return '数字超出可表示范围'
      return null
    }
    case 'json': {
      if (value.trim() === '') return 'JSON 不能为空'
      try {
        JSON.parse(value)
      } catch {
        return 'JSON 格式不正确'
      }
      return null
    }
    default:
      return null
  }
}

/** 保存前把用户输入规范化（布尔小写、JSON 压缩） */
export function normalizeConfigValue(type: unknown, raw: unknown): string {
  const value = String(raw ?? '')
  switch (configControl(type)) {
    case 'boolean':
      return value.trim().toLowerCase()
    case 'json':
      try {
        return JSON.stringify(JSON.parse(value))
      } catch {
        return value
      }
    default:
      return value
  }
}

/** 格式化 JSON（失败时原样返回并给出错误） */
export function formatJsonText(raw: unknown): { ok: boolean; text: string; error?: string } {
  const value = String(raw ?? '')
  if (value.trim() === '') return { ok: false, text: value, error: 'JSON 不能为空' }
  try {
    return { ok: true, text: JSON.stringify(JSON.parse(value), null, 2) }
  } catch (e) {
    return { ok: false, text: value, error: e instanceof Error ? e.message : String(e) }
  }
}
