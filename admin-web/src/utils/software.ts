/**
 * 软件分发页（task-90）的纯函数工具：格式化/校验/排序/状态判定。
 * 全是无副作用的纯函数，方便 node:test 单测（见 tests/software.test.ts）。
 * 字段口径以 task-90 rev4 的真实契约为准：
 *  - 包列表：code/name/description/platform/latestVersion/releaseCount/updatedAt（无 id、无 createdAt）
 *  - 版本列表（task-92 提供）：id/packageCode/version/channel/fileName/fileSize/sha256/releaseNotes/downloadCount/publishedAt/createdAt
 */

export interface PlatformOption {
  value: string
  label: string
}

/** 平台下拉选项（后端 platform 是自由字符串，这里只给常用值） */
export const PLATFORM_OPTIONS: PlatformOption[] = [
  { value: 'any', label: '通用' },
  { value: 'windows', label: 'Windows' },
  { value: 'macos', label: 'macOS' },
  { value: 'linux', label: 'Linux' },
  { value: 'android', label: 'Android' },
  { value: 'ios', label: 'iOS' }
]

export interface ChannelOption {
  value: string
  label: string
}

/** 渠道下拉选项：后端默认 stable，只做 stable/beta 两种 */
export const CHANNEL_OPTIONS: ChannelOption[] = [
  { value: 'stable', label: 'stable · 稳定版' },
  { value: 'beta', label: 'beta · 测试版' }
]

const PLATFORM_LABELS: Record<string, string> = {
  any: '通用',
  windows: 'Windows',
  macos: 'macOS',
  linux: 'Linux',
  android: 'Android',
  ios: 'iOS'
}

/** 平台展示名：已知平台给中文/惯用写法，未知回退原值（不要猜成「通用」） */
export function platformLabel(platform?: string | null): string {
  if (!platform) return '—'
  return PLATFORM_LABELS[platform.toLowerCase()] ?? platform
}

/** 渠道展示名：stable=稳定版 / beta=测试版，其它原样返回 */
export function channelLabel(channel?: string | null): string {
  if (!channel) return '—'
  if (channel === 'stable') return 'stable'
  if (channel === 'beta') return 'beta'
  return channel
}

export interface ReleaseStatus {
  label: string
  color: 'success' | 'warning' | 'default'
  published: boolean
}

/** 版本状态：DTO 没有 published 布尔字段，publishedAt=null 即未发布 */
export function releaseStatus(publishedAt?: string | null): ReleaseStatus {
  if (publishedAt) return { label: '已发布', color: 'success', published: true }
  return { label: '未发布', color: 'default', published: false }
}

/** 是否已发布 */
export function isPublished(publishedAt?: string | null): boolean {
  return !!publishedAt
}

/**
 * 版本列表排序：createdAt 降序、id 降序（与 task-92 的后端排序一致）。
 * id 是 17 位雪花字符串，同长度时字符串比较等价于数值比较；不做 Number() 转换。
 */
export function sortReleases<T extends { id: string; createdAt?: string | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    const ca = a.createdAt ?? ''
    const cb = b.createdAt ?? ''
    if (ca !== cb) return cb.localeCompare(ca)
    return b.id.localeCompare(a.id)
  })
}

/** 包列表排序：最近更新的在前（updatedAt 降序），updatedAt 相同时按 code 升序 */
export function sortPackages<T extends { code: string; updatedAt?: string | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    const ua = a.updatedAt ?? ''
    const ub = b.updatedAt ?? ''
    if (ua !== ub) return ub.localeCompare(ua)
    return a.code.localeCompare(b.code)
  })
}

/**
 * 选「最新已发布版本」用于「下载」按钮：
 * 1) 优先精确匹配包的 latestVersion（后端已发布最新版）；
 * 2) 找不到时回退到已发布版本里 createdAt 最新的一个；
 * 3) 一个已发布版本都没有 → null（页面提示「还没有已发布的版本」，不猜、不下载未发布版本）。
 */
export function latestPublishedRelease<T extends { id: string; version: string; publishedAt?: string | null; createdAt?: string | null }>(
  releases: T[],
  latestVersion?: string | null
): T | null {
  const published = sortReleases(releases.filter((r) => isPublished(r.publishedAt)))
  if (!published.length) return null
  if (latestVersion) {
    const exact = published.find((r) => r.version === latestVersion)
    if (exact) return exact
  }
  return published[0] ?? null
}

/**
 * 上传进度百分比：total 未知时返回 null（调用方显示「上传中」而不是假进度）。
 * 结果钳制在 0~100。
 */
export function uploadPercent(loaded?: number | null, total?: number | null): number | null {
  if (loaded == null || total == null || total <= 0) return null
  const pct = Math.round((loaded / total) * 100)
  if (Number.isNaN(pct)) return null
  return Math.min(100, Math.max(0, pct))
}

/** 包 code 前缀校验：后端唯一键，前端先做一遍（2-64 位小写字母/数字/中划线，字母开头） */
export function validatePackageCode(code: string): string | null {
  if (!code.trim()) return '请输入包 code'
  if (!/^[a-z][a-z0-9-]{1,63}$/.test(code.trim())) {
    return '包 code 需以小写字母开头，只含小写字母、数字、中划线，长度 2-64'
  }
  return null
}

/** 版本号校验：主版本.次版本.修订号，可带 -beta.1 之类的后缀 */
export function validateVersion(version: string): string | null {
  if (!version.trim()) return '请输入版本号'
  if (!/^\d+\.\d+\.\d+([.-][0-9A-Za-z.-]+)?$/.test(version.trim())) {
    return '版本号需形如 1.2.3 或 1.2.3-beta.1'
  }
  return null
}

/** 发布说明上限（后端没限制，前端限制输入长度避免误贴大段文本） */
export const RELEASE_NOTES_MAX = 2000

/** 包删除冲突文案：DELETE /packages/{code} 有版本时返回 409 */
export const PACKAGE_DELETE_CONFLICT_TEXT = '该包还有版本，请先删除所有版本'

/** 根据 HTTP 状态码给出删除包的文案：409 → 明确提示；其它交给后端 message 原文 */
export function packageDeleteText(status: number | undefined, backendMessage?: string): string {
  if (status === 409) return PACKAGE_DELETE_CONFLICT_TEXT
  if (backendMessage) return backendMessage
  return '删除失败'
}

/** 包列表摘要行：平台 · 最新版本 · 版本总数（实测 releaseCount=全部版本数，含未发布） */
export function packageMetaText(pkg: { platform?: string | null; latestVersion?: string | null; releaseCount?: number | null }): string {
  const platform = platformLabel(pkg.platform)
  const version = pkg.latestVersion ? `最新 ${pkg.latestVersion}` : '暂无已发布版本'
  const count = `${pkg.releaseCount ?? 0} 个版本`
  return `${platform} · ${version} · ${count}`
}

/** 文件大小展示（复用 formatBytes 的口径，单独导出便于测试） */
export function releaseSizeText(fileSize?: number | null): string {
  if (fileSize == null || Number.isNaN(fileSize) || fileSize < 0) return '—'
  if (fileSize === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = fileSize
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i += 1
  }
  const digits = value >= 100 || i === 0 ? 0 : value >= 10 ? 1 : 2
  return `${value.toFixed(digits)} ${units[i]}`
}

/** sha256 展示：保留完整（可复制），但列表用短形式 8 位 + … */
export function shortSha256(sha?: string | null, head = 8): string {
  if (!sha) return '—'
  if (sha.length <= head) return sha
  return `${sha.slice(0, head)}…`
}

/** 上传对话框的版本号自动建议：基于已有版本让用户改，不自动填假版本 */
export function suggestBetaVersion(base?: string | null): string {
  if (!base) return '1.0.0-beta.1'
  const m = base.match(/^(\d+)\.(\d+)\.(\d+)/)
  if (!m) return `${base}-beta.1`
  return `${m[1]}.${m[2]}.${Number(m[3]) + 1}-beta.1`
}

/** 包列表搜索：code / 名称 / 平台（大小写不敏感；空关键词全匹配） */
export function matchPackage(
  pkg: { code?: string | null; name?: string | null; platform?: string | null },
  keyword: string
): boolean {
  const kw = keyword.trim().toLowerCase()
  if (!kw) return true
  return (
    String(pkg.code ?? '').toLowerCase().includes(kw) ||
    String(pkg.name ?? '').toLowerCase().includes(kw) ||
    platformLabel(pkg.platform).toLowerCase().includes(kw)
  )
}
