/** 邮件模板的变量解析与分组（纯函数，便于单测） */
import type { MailPurpose } from '../types'

/** 匹配 {{ var }}，允许大写、下划线、数字、点 */
const VAR_PATTERN = /\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g

/**
 * 从若干段文本里提取模板变量（去重，保留首次出现的顺序）。
 * 注意：每次调用内部新建正则，避免 lastIndex 状态复用导致的漏匹配。
 */
export function extractTemplateVars(...texts: Array<string | null | undefined>): string[] {
  const seen = new Set<string>()
  const list: string[] = []
  for (const text of texts) {
    if (!text) continue
    const re = new RegExp(VAR_PATTERN.source, 'g')
    let match: RegExpExecArray | null
    while ((match = re.exec(text)) !== null) {
      const name = match[1]
      if (!seen.has(name)) {
        seen.add(name)
        list.push(name)
      }
    }
  }
  return list
}

/** 引用但未声明的变量（保存前校验用；declared 为空表示不校验） */
export function unknownTemplateVars(referenced: string[], declared: string[]): string[] {
  if (!declared.length) return []
  const known = new Set(declared)
  return referenced.filter((name) => !known.has(name))
}

/** 模板变量在 UI 里的展示形式 */
export function templateVarText(name: string): string {
  return `{{${name}}}`
}

/** 该模板被哪些用途绑定/引用 */
export function templatePurposes(purposes: MailPurpose[], code: string): MailPurpose[] {
  return purposes.filter((p) => p.boundTemplateCode === code || p.builtinTemplateCode === code)
}

/** 用途的展示文案：页面上显示「邮件用途」 */
export function purposeText(purpose: MailPurpose): string {
  return purpose.label || purpose.purpose
}

/** 「用途」列的聚合文案 */
export function templatePurposeText(purposes: MailPurpose[], code: string): string {
  const list = templatePurposes(purposes, code)
  if (!list.length) return '未绑定用途'
  return list.map(purposeText).join('、')
}

export interface TemplateGroups<T> {
  builtin: T[]
  custom: T[]
}

/** 列表分组：内置（8 个系统模板）与自定义（用户新建） */
export function groupTemplates<T extends { isBuiltin: boolean }>(rows: T[]): TemplateGroups<T> {
  const groups: TemplateGroups<T> = { builtin: [], custom: [] }
  for (const row of rows) {
    if (row.isBuiltin) groups.builtin.push(row)
    else groups.custom.push(row)
  }
  return groups
}

/** 保存前校验变量：编辑内置/已有模板时，正文里引用的变量必须在该模板变量列表里 */
export function validateTemplateVariables(
  texts: Array<string | null | undefined>,
  declared: string[]
): { ok: true } | { ok: false; unknown: string[] } {
  const referenced = extractTemplateVars(...texts)
  const unknown = unknownTemplateVars(referenced, declared)
  return unknown.length ? { ok: false, unknown } : { ok: true }
}

/** 自定义模板 code 的显示（后端生成 custom_xxxxxxxx） */
export function isCustomTemplateCode(code: string): boolean {
  return /^custom_/.test(code)
}

/** 采样变量：给预览表单预填一组示例值（字符串值，纯展示用途） */
export function sampleVariableValue(name: string, now?: Date): string {
  const d = now ?? new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  const lower = name.toLowerCase()
  if (lower.includes('code')) return '123456'
  if (lower.includes('email')) return 'admin@example.com'
  if (lower.includes('username') || lower.includes('nickname')) return 'Bond 用户'
  if (lower.includes('minute')) return '15'
  if (lower.includes('time') || lower.includes('date') || lower.includes('at')) return stamp
  if (lower.includes('link') || lower.includes('url')) return 'https://pan.example.com/share/demo'
  if (lower.includes('plan')) return 'Plus'
  if (lower.includes('size') || lower.includes('bytes')) return '104857600'
  return `示例 ${name}`
}
