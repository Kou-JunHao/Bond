/**
 * 档位与状态字典。
 * 档位来源：bond-common/src/main/resources/db/migration/V3__entitlements.sql（plans 表）
 * free / plus / team 三档；权益键值与 limits JSONB 一致。
 */

export interface PlanOption {
  code: string
  name: string
  priceCents: number
  storageBytes: number
  maxFileBytes: number
  maxWorkspaces: number
  maxMembers: number
  relayBytesMonth: number
  color: string
}

export const PLANS: PlanOption[] = [
  {
    code: 'free',
    name: 'Free',
    priceCents: 0,
    storageBytes: 5 * 1024 ** 3,
    maxFileBytes: 2 * 1024 ** 3,
    maxWorkspaces: 1,
    maxMembers: 3,
    relayBytesMonth: 10 * 1024 ** 3,
    color: 'default'
  },
  {
    code: 'plus',
    name: 'Plus',
    priceCents: 3000,
    storageBytes: 200 * 1024 ** 3,
    maxFileBytes: 10 * 1024 ** 3,
    maxWorkspaces: 5,
    maxMembers: 10,
    relayBytesMonth: 200 * 1024 ** 3,
    color: 'blue'
  },
  {
    code: 'team',
    name: 'Team',
    priceCents: 9800,
    storageBytes: 1024 ** 4,
    maxFileBytes: 20 * 1024 ** 3,
    maxWorkspaces: 20,
    maxMembers: 50,
    relayBytesMonth: 600 * 1024 ** 3,
    color: 'purple'
  }
]

export const PLAN_MAP: Record<string, PlanOption> = Object.fromEntries(PLANS.map((p) => [p.code, p]))

export function planName(code?: string | null): string {
  if (!code) return '未设置'
  return PLAN_MAP[code]?.name || code
}

export function planColor(code?: string | null): string {
  if (!code) return 'default'
  return PLAN_MAP[code]?.color || 'default'
}

/** 加量/开白（user_entitlements）支持键值 */
export const GRANT_KEYS: Array<{ key: string; label: string; hint: string }> = [
  { key: 'storage_bytes', label: '存储空间', hint: '字节，0 表示关闭；写入后立即刷新配额' },
  { key: 'relay_bytes_month', label: '月度中继流量', hint: '字节/月' },
  { key: 'max_file_size', label: '单文件上限', hint: '字节' },
  { key: 'concurrent_transfers', label: '并发传输数', hint: '个' },
  { key: 'max_workspaces', label: '工作区数量上限', hint: '个' },
  { key: 'max_workspace_members', label: '工作区成员上限', hint: '人' },
  { key: 'relay_down_bytes_per_sec', label: '中继下行限速', hint: '字节/秒，0 表示不限速' },
  { key: 'share_expires_days', label: '分享有效期', hint: '天，0 表示永久' },
  { key: 'grace_days', label: '欠费宽限期', hint: '天' }
]

export interface DictItem {
  label: string
  color?: string
}

/** 传输任务状态（TransferTask.status） */
export const TRANSFER_STATUS: Record<number, DictItem> = {
  0: { label: '等待中', color: 'default' },
  1: { label: '传输中', color: 'processing' },
  2: { label: '已完成', color: 'success' },
  3: { label: '失败', color: 'error' }
}

/** 用户状态（users.status）：1 正常，0 禁用 */
export const USER_STATUS: Record<number, DictItem> = {
  0: { label: '已禁用', color: 'error' },
  1: { label: '正常', color: 'success' }
}

export function userStatusLabel(status?: number | null): DictItem {
  if (status == null) return { label: '未知', color: 'default' }
  return USER_STATUS[status] || { label: `状态 ${status}`, color: 'default' }
}

/** 公告级别 */
export const ANNOUNCEMENT_LEVELS: Array<DictItem & { value: string }> = [
  { value: 'info', label: '普通', color: 'blue' },
  { value: 'warn', label: '重要', color: 'orange' },
  { value: 'critical', label: '紧急', color: 'red' }
]

export function levelInfo(level?: string | null) {
  return ANNOUNCEMENT_LEVELS.find((l) => l.value === level) || ANNOUNCEMENT_LEVELS[0]
}

/** 审计动作中文名（后端 @AdminAudit action 字符串） */
export const AUDIT_ACTIONS: Record<string, string> = {
  'user.status.set': '修改用户状态',
  'user.admin.set': '设置/取消管理员',
  'user.delete': '删除用户',
  'user.plan.set': '调整用户档位',
  'user.grant': '加量/开白',
  'user.email.update': '修改用户邮箱',
  'user.password.reset': '重置用户密码',
  'redeem_codes.create': '生成兑换码',
  'config.update': '修改配置',
  'config.batch_update': '批量修改配置',
  'config.reset': '重置配置',
  'transfer.delete': '删除传输任务',
  'transfer.cleanup': '清理过期传输',
  'workspace.delete': '删除工作区',
  'announcement.create': '发布公告',
  'announcement.update': '修改公告',
  'announcement.delete': '删除公告'
}

export function auditActionLabel(action?: string | null): string {
  if (!action) return '—'
  return AUDIT_ACTIONS[action] || action
}
