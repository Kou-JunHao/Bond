import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import router from '../router'

const http = axios.create({ baseURL: '/api' })

http.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  res => res.data,
  err => {
    if (err.response?.status === 401) {
      useAuthStore().logout()
      router.push('/login')
    }
    return Promise.reject(err)
  }
)

export default http

export const adminApi = {
  login: (username: string, password: string, captchaId?: string, captchaCode?: string) => {
    const payload: any = { username, password }
    if (captchaId) payload.captchaId = captchaId
    if (captchaCode) payload.captchaCode = captchaCode
    return http.post('/auth/login', payload)
  },
  getCaptcha: () =>
    http.get('/auth/captcha'),
  checkCaptcha: () =>
    http.get('/auth/captcha/check'),
  getMe: () =>
    http.get('/auth/me'),
  updateProfile: (nickname?: string, avatarUrl?: string) => {
    const body: any = {}
    if (nickname !== undefined) body.nickname = nickname
    if (avatarUrl !== undefined) body.avatarUrl = avatarUrl
    return http.put('/auth/profile', body)
  },
  uploadAvatar: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/transfer/avatar', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  dashboard: () =>
    http.get('/admin/dashboard'),
  getConfig: () =>
    http.get('/admin/config'),
  getConfigByKey: (key: string) =>
    http.get(`/admin/config/${key}`),
  updateConfig: (key: string, value: string) =>
    http.put(`/admin/config/${key}`, { value }),
  batchUpdateConfig: (configs: Record<string, string>) =>
    http.put('/admin/config', configs),
  resetConfig: () =>
    http.post('/admin/config/reset'),
  getUsers: (params: any) =>
    http.get('/admin/users', { params }),
  getUserDetail: (id: number) =>
    http.get(`/admin/users/${id}`),
  getUserStats: () =>
    http.get('/admin/users/stats'),
  setUserStatus: (id: number, status: number) =>
    http.put(`/admin/users/${id}/status`, { status }),
  setAdmin: (id: number, isAdmin: boolean) =>
    http.put(`/admin/users/${id}/admin`, { isAdmin }),
  deleteUser: (id: number) =>
    http.delete(`/admin/users/${id}`),
  getTransfers: (params: any) =>
    http.get('/admin/transfers', { params }),
  getTransferStats: () =>
    http.get('/admin/transfers/stats'),
  getTransferTrend: (days: number = 7) =>
    http.get('/admin/dashboard/transfer-trend', { params: { days } }),
  deleteTransfer: (id: number) =>
    http.delete(`/admin/transfers/${id}`),
  cleanupTransfers: () =>
    http.post('/admin/transfers/cleanup'),
  getWorkspaces: (params: any) =>
    http.get('/admin/workspaces', { params }),
  getWorkspaceDetail: (id: number) =>
    http.get(`/admin/workspaces/${id}`),
  deleteWorkspace: (id: number) =>
    http.delete(`/admin/workspaces/${id}`)
}
