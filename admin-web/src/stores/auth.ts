import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { CurrentUser } from '../types'

export interface AuthUser {
  id?: string
  username: string
  nickname?: string | null
  avatarUrl?: string | null
}

const TOKEN_KEY = 'admin_token'
const USERNAME_KEY = 'admin_username'
const USER_KEY = 'admin_user'

function readUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<AuthUser | null>(readUser())
  const username = computed(() => user.value?.username || localStorage.getItem(USERNAME_KEY) || '')
  const displayName = computed(() => user.value?.nickname || user.value?.username || username.value || '管理员')

  function setAuth(t: string, u: AuthUser) {
    token.value = t
    user.value = u
    localStorage.setItem(TOKEN_KEY, t)
    localStorage.setItem(USERNAME_KEY, u.username)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  function setUser(u: CurrentUser | AuthUser) {
    const merged: AuthUser = {
      id: u.id,
      username: u.username,
      nickname: u.nickname ?? null,
      avatarUrl: u.avatarUrl ?? null
    }
    user.value = merged
    localStorage.setItem(USERNAME_KEY, merged.username)
    localStorage.setItem(USER_KEY, JSON.stringify(merged))
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, username, displayName, setAuth, setUser, logout }
})
