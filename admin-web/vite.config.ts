import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 版本号只来自 package.json（构建期注入）；取不到就注入空字符串，界面据此隐藏版本号，绝不回退成写死的版本。
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8')) as { version?: string }
const appVersion = pkg.version || ''
// 环境名来自构建参数 APP_ENV，默认 production；界面只做展示映射，不猜环境。
const appEnv = process.env.APP_ENV || 'production'

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
    __APP_ENV__: JSON.stringify(appEnv)
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
