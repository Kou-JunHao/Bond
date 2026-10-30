/** 前端 SHA-256 计算（仅用于上传前展示；最终以服务端计算结果为准） */

/** ArrayBuffer → 小写十六进制 */
export function toHex(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let out = ''
  for (const b of bytes) out += b.toString(16).padStart(2, '0')
  return out
}

/** 计算 SHA-256（依赖浏览器/Node 的 WebCrypto） */
export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data)
  return toHex(digest)
}

/** 前端计算大文件哈希的上限：超过则跳过（避免卡死浏览器），由后端计算 */
export const FRONTEND_HASH_LIMIT = 256 * 1024 * 1024

/** 超出上限时返回提示文案，未超出返回 null */
export function hashSkipHint(size: number, limit = FRONTEND_HASH_LIMIT): string | null {
  return size > limit ? '文件超过 256 MB，前端不计算 SHA-256（以服务端返回为准）' : null
}

/** 服务端返回的 sha256 在前端校验格式（64 位十六进制） */
export function isValidSha256(value: unknown): boolean {
  return typeof value === 'string' && /^[0-9a-f]{64}$/i.test(value.trim())
}
