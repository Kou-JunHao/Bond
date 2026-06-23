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
  login: (username: string, password: string) =>
    http.post('/auth/login', { username, password }),
  dashboard: () =>
    http.get('/admin/dashboard'),
  getConfig: () =>
    http.get('/admin/config'),
  updateConfig: (key: string, value: string) =>
    http.put(`/admin/config/${key}`, { value }),
  resetConfig: () =>
    http.post('/admin/config/reset'),
  getUsers: (params: any) =>
    http.get('/admin/users', { params }),
  getUserDetail: (id: number) =>
    http.get(`/admin/users/${id}`),
  setUserStatus: (id: number, status: number) =>
    http.put(`/admin/users/${id}/status`, { status }),
  setAdmin: (id: number, isAdmin: boolean) =>
    http.put(`/admin/users/${id}/admin`, { isAdmin }),
  deleteUser: (id: number) =>
    http.delete(`/admin/users/${id}`),
  getTransfers: (params: any) =>
    http.get('/admin/transfers', { params }),
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
