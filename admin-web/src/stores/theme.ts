import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const STORAGE_KEY = 'bond-admin-theme'
const ATTR = 'data-theme'

export type ThemeMode = 'light' | 'dark'

function initialMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(initialMode())
  const isDark = computed(() => mode.value === 'dark')

  function apply() {
    document.documentElement.setAttribute(ATTR, mode.value)
    // ant-design-vue 的暗色组件（如弹出层）依赖 body 上的 class 时改用 token；这里仅同步根属性
  }

  function init() {
    apply()
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    localStorage.setItem(STORAGE_KEY, next)
    apply()
  }

  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  return { mode, isDark, init, setMode, toggle }
})
