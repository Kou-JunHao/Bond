<template>
  <div class="login-shell">
    <section class="brand-panel">
      <div class="brand-inner">
        <div class="brand-mark"><img :src="logoUrl" alt="Bond" /></div>
        <h1 class="brand-title">Bond 管理后台</h1>
        <p class="brand-sub">文件传输 · 团队工作区 · 权限与配置</p>

        <ul class="brand-points">
          <li>
            <span class="point-icon"><ThunderboltOutlined /></span>
            <div><strong>端到端加密传输</strong><span>跨设备直连，服务器仅中转密文</span></div>
          </li>
          <li>
            <span class="point-icon"><SafetyCertificateOutlined /></span>
            <div><strong>集中式管理</strong><span>用户、工作区、任务、公告一处掌控</span></div>
          </li>
          <li>
            <span class="point-icon"><GlobalOutlined /></span>
            <div><strong>实时数据</strong><span>直连生产 API，操作即时生效</span></div>
          </li>
        </ul>

        <div class="brand-foot"><span class="dot" /> Bond Platform · Admin Console</div>
      </div>
    </section>

    <section class="form-panel">
      <div class="form-card">
        <div class="form-head">
          <h2>登录控制台</h2>
          <p>使用管理员账号登录 Bond 管理后台（普通用户请前往客户端站点）</p>
        </div>

        <a-form :model="form" :rules="rules" layout="vertical" @finish="onSubmit">
          <a-form-item label="用户名" name="username">
            <a-input v-model:value="form.username" size="large" placeholder="请输入管理员用户名"
              autocomplete="username" allow-clear>
              <template #prefix><UserOutlined class="input-icon" /></template>
            </a-input>
          </a-form-item>

          <a-form-item label="密码" name="password">
            <a-input-password v-model:value="form.password" size="large" placeholder="请输入登录密码"
              autocomplete="current-password">
              <template #prefix><LockOutlined class="input-icon" /></template>
            </a-input-password>
          </a-form-item>

          <a-form-item v-if="needsCaptcha" label="验证码" name="captchaCode">
            <div class="captcha-row">
              <a-input v-model:value="form.captchaCode" size="large" placeholder="请输入图中字符"
                :maxlength="6" @press-enter="onSubmit" />
              <div class="captcha-img" title="点击刷新验证码" @click="loadCaptcha">
                <img v-if="captcha?.image" :src="captcha.image" alt="验证码" />
                <span v-else class="captcha-loading"><LoadingOutlined spin /></span>
              </div>
            </div>
          </a-form-item>

          <a-alert v-if="errorMsg" type="error" :message="errorMsg" show-icon class="login-error" />

          <a-button type="primary" html-type="submit" size="large" block :loading="submitting" class="login-submit">
            登 录
          </a-button>
          <p class="login-hint">仅管理员账号可登录，非管理员账号会被拒绝</p>
        </a-form>

        <div class="form-foot">
          <span><ApiOutlined /> API：bond-api.sg.skkk.uno</span>
          <a href="https://bond.sg.skkk.uno" target="_blank" rel="noopener">前往客户端站点 <ArrowRightOutlined /></a>
        </div>
      </div>

      <footer class="page-foot">© {{ year }} Bond · 仅限授权管理员访问</footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  ApiOutlined, ArrowRightOutlined, GlobalOutlined, LoadingOutlined, LockOutlined,
  SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined
} from '@ant-design/icons-vue'
import http, { adminApi } from '../api/admin'
import logoUrl from '../assets/logo.svg'
import { useAuthStore } from '../stores/auth'
import { formatApiError, isForbidden } from '../utils/errors'
import type { CaptchaData } from '../types'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const year = new Date().getFullYear()
const form = reactive({ username: '', password: '', captchaCode: '' })
const submitting = ref(false)
const errorMsg = ref('')
const needsCaptcha = ref(false)
const captcha = ref<CaptchaData | null>(null)
const captchaId = ref('')

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

async function checkCaptcha() {
  try {
    const res = await adminApi.checkCaptcha()
    const data = res.data as unknown
    needsCaptcha.value =
      typeof data === 'boolean' ? data : Boolean((data as { needsCaptcha?: boolean })?.needsCaptcha)
    if (needsCaptcha.value && !captcha.value) await loadCaptcha()
  } catch {
    // 验证码探测失败不阻塞登录（服务端仍会按需索要验证码）
  }
}

async function loadCaptcha() {
  try {
    const res = await adminApi.getCaptcha()
    captcha.value = res.data
    captchaId.value = res.data?.id || ''
    form.captchaCode = ''
  } catch {
    message.warning('验证码加载失败，请稍后重试')
  }
}

const NO_ADMIN_TEXT = '该账号没有管理员权限，请使用管理员账号登录'

/**
 * 登录后立刻校验管理员身份：
 * /api/auth/login 不校验 is_admin，非管理员能登录成功，但访问 /api/admin/** 会 403。
 * 这里主动探一次 dashboard，403 时清 token 留在登录页并明确提示（不显示 axios 原文）。
 */
async function assertAdmin(): Promise<{ ok: boolean; message?: string }> {
  try {
    await http.get('/admin/dashboard', { headers: { 'X-Silence-403': '1' } })
    return { ok: true }
  } catch (err) {
    if (isForbidden(err)) return { ok: false, message: NO_ADMIN_TEXT }
    // 网络抖动/5xx 不阻断登录：后续页面自己会展示可读错误
    return { ok: true }
  }
}

async function onSubmit() {
  if (submitting.value) return
  errorMsg.value = ''
  submitting.value = true
  try {
    const res = await adminApi.login(
      form.username.trim(),
      form.password,
      needsCaptcha.value ? captchaId.value : undefined,
      needsCaptcha.value ? form.captchaCode.trim() : undefined
    )
    if (res.code !== 200 || !res.data?.accessToken) throw new Error(res.message || '登录失败')
    const user = res.data
    auth.setAuth(user.accessToken, {
      id: user.id, username: user.username, nickname: user.nickname, avatarUrl: user.avatarUrl
    })
    const check = await assertAdmin()
    if (!check.ok) {
      auth.logout()
      errorMsg.value = check.message || NO_ADMIN_TEXT
      message.error(errorMsg.value)
      return
    }
    message.success(`欢迎回来，${user.nickname || user.username}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.replace(redirect)
  } catch (err) {
    errorMsg.value = formatApiError(err, '登录失败，请检查用户名与密码')
    message.error(errorMsg.value)
    if (needsCaptcha.value) await loadCaptcha()
    await checkCaptcha()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  checkCaptcha()
})
</script>

<style scoped>
.login-shell { display: flex; min-height: 100vh; background: var(--app-bg); }

.brand-panel {
  position: relative; flex: 1 1 54%; min-width: 0; overflow: hidden;
  display: flex; align-items: center; justify-content: center; padding: 72px 64px; color: #fff;
  background: #2b1d18;
}
.brand-inner { position: relative; z-index: 1; max-width: 560px; width: 100%; }

.brand-mark {
  width: 64px; height: 64px; border-radius: 16px; padding: 10px;
  background: #ffffff;
  box-shadow: 0 10px 28px -18px rgba(0, 0, 0, 0.5);
  display: flex; align-items: center; justify-content: center;
}
.brand-mark img { width: 100%; height: 100%; object-fit: contain; display: block; }
.brand-title { margin: 26px 0 10px; font-size: 40px; font-weight: 700; letter-spacing: 0.5px; color: #fff; text-shadow: 0 2px 18px rgba(0, 0, 0, 0.25); }
.brand-sub { margin: 0; font-size: 15px; letter-spacing: 1px; color: rgba(255, 255, 255, 0.72); }

.brand-points { list-style: none; padding: 0; margin: 44px 0 0; display: flex; flex-direction: column; gap: 20px; }
.brand-points li { display: flex; gap: 14px; align-items: flex-start; }
.point-icon {
  flex: none; width: 36px; height: 36px; border-radius: 10px; display: inline-flex;
  align-items: center; justify-content: center; font-size: 17px; color: #ffd9cc;
  background: rgba(255, 255, 255, 0.1); border: 1px solid rgba(255, 255, 255, 0.14);
}
.brand-points strong { display: block; font-size: 14px; font-weight: 600; margin-bottom: 3px; }
.brand-points span { font-size: 12.5px; color: rgba(255, 255, 255, 0.62); }

.brand-foot { margin-top: 56px; font-size: 12px; letter-spacing: 0.6px; color: rgba(255, 255, 255, 0.45); display: flex; align-items: center; gap: 8px; }
.brand-foot .dot { width: 6px; height: 6px; border-radius: 50%; background: #7ee2a8; box-shadow: 0 0 0 4px rgba(126, 226, 168, 0.15); }

.form-panel { flex: 0 0 46%; min-width: 420px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px; position: relative; }
.form-card {
  width: 100%; max-width: 420px; background: var(--app-card-bg); border: 1px solid var(--app-border);
  border-radius: 20px; padding: 40px 36px 28px;
  box-shadow: 0 24px 60px -30px rgba(31, 35, 41, 0.28), 0 2px 8px rgba(31, 35, 41, 0.04);
}
.form-head { margin-bottom: 28px; }
.form-head h2 { margin: 0 0 8px; font-size: 24px; font-weight: 650; color: var(--app-text); }
.form-head p { margin: 0; font-size: 13px; color: var(--app-text-secondary); }
.input-icon { color: var(--app-text-secondary); }

.captcha-row { display: flex; gap: 12px; }
.captcha-img {
  flex: none; width: 118px; height: 40px; border-radius: 8px; overflow: hidden; cursor: pointer;
  border: 1px solid var(--app-border); background: #f4f5f7; display: flex; align-items: center; justify-content: center;
}
.captcha-img img { width: 100%; height: 100%; object-fit: cover; display: block; }
.captcha-loading { color: var(--app-text-secondary); }
.login-error { margin-bottom: 16px; }
.login-submit { height: 44px; font-size: 15px; font-weight: 600; letter-spacing: 4px; margin-top: 4px; box-shadow: 0 10px 24px -10px rgba(229, 113, 83, 0.75); }
.login-hint { margin: 12px 0 0; text-align: center; font-size: 12px; color: var(--app-text-secondary); }

.form-foot { margin-top: 26px; padding-top: 16px; border-top: 1px dashed var(--app-border); display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--app-text-secondary); }
.form-foot a { font-size: 12px; white-space: nowrap; }
.page-foot { position: absolute; bottom: 24px; font-size: 12px; color: var(--app-text-secondary); opacity: 0.8; }

@media (max-width: 1024px) {
  .brand-panel { display: none; }
  .form-panel { flex: 1; min-width: 0; padding: 24px; }
  .form-card { padding: 32px 26px 24px; }
}
</style>
