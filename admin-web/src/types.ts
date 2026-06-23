export interface ApiResult<T = any> {
  code: number
  message: string
  data: T
}

export interface User {
  id: number
  username: string
  nickname: string
  avatarUrl?: string
  status: number
  isAdmin: boolean
  deviceCount?: number
  transferCount?: number
  createdAt?: number
}

export interface TransferTask {
  id: number
  senderId: number
  receiverId: number
  fileName: string
  fileSize: number
  chunkCount: number
  uploadedChunks: number
  status: number
  createdAt?: number
}

export interface Workspace {
  id: number
  name: string
  description?: string
  ownerId: number
  maxSize: number
  usedSize: number
  memberCount?: number
  fileCount?: number
  createdAt?: number
}

export interface SystemConfig {
  configKey: string
  configValue: string
  configType: 'string' | 'int' | 'long' | 'boolean'
  description?: string
}

export interface DashboardData {
  totalUsers: number
  activeUsers: number
  totalTransfers: number
  completedTransfers: number
  totalWorkspaces: number
  totalStorageUsed: number
  transferTrend: Record<string, number>
}

export interface TransferStats {
  total: number
  pending: number
  uploading: number
  completed: number
  failed: number
  expired: number
}

export interface Device {
  id: number
  deviceName: string
  deviceType: string
  isOnline: boolean
  lastSeenAt?: number
}

export interface CaptchaData {
  id: string
  image: string
}
