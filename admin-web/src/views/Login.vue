<template>
  <div class="login-page">
    <div class="login-container">
      <div class="login-header">
        <h1>Bond Admin</h1>
        <p>跨网络文件传输管理系统</p>
      </div>
      <a-form :model="form" @finish="handleLogin" layout="vertical" size="large">
        <a-form-item label="用户名" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="form.username" placeholder="请输入管理员用户名">
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>
        <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" placeholder="请输入密码" @pressEnter="handleLogin">
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item v-if="showCaptcha" label="验证码">
          <div class="captcha-row">
            <a-input v-model:value="form.captchaCode" placeholder="请输入验证码" @pressEnter="handleLogin" />
            <img v-if="captchaImg" :src="captchaImg" class="captcha-img" @click="loadCaptcha" title="点击刷新" />
          </div>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block>登录</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const showCaptcha = ref(false)
const captchaImg = ref('')
const captchaId = ref('')
const form = reactive({ username: '', password: '', captchaCode: '' })

async function checkCaptchaNeeded() {
  try {
    const res: any = await adminApi.checkCaptcha()
    if (res.code === 200 && res.data?.needsCaptcha) { showCaptcha.value = true; loadCaptcha() }
  } catch {}
}

async function loadCaptcha() {
  try {
    const res: any = await adminApi.getCaptcha()
    if (res.code === 200) { captchaImg.value = res.data.image; captchaId.value = res.data.id }
  } catch {}
}

async function handleLogin() {
  if (!form.username || !form.password) { message.warning('请输入用户名和密码'); return }
  if (showCaptcha.value && !form.captchaCode) { message.warning('请输入验证码'); return }

  loading.value = true
  try {
    const payload: any = { username: form.username, password: form.password }
    if (showCaptcha.value) { payload.captchaId = captchaId.value; payload.captchaCode = form.captchaCode }
    const res: any = await adminApi.login(payload.username, payload.password, payload.captchaId, payload.captchaCode)
    if (res.code === 200 && res.data?.accessToken) {
      auth.setAuth(res.data.accessToken, form.username)
      router.push('/dashboard')
    } else {
      message.error(res.message || '登录失败')
      if (res.message?.includes('验证码') || res.message?.includes('需要')) { showCaptcha.value = true; loadCaptcha() }
    }
  } catch (e: any) {
    const msg = e.response?.data?.message || '登录失败'
    message.error(msg)
    if (msg.includes('验证码') || msg.includes('需要')) { showCaptcha.value = true; loadCaptcha() }
  } finally { loading.value = false }
}

onMounted(checkCaptchaNeeded)
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
  padding: 24px;
}

.login-container {
  width: 100%;
  max-width: 400px;
  background: #fff;
  padding: 40px 32px 24px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 28px;
  font-weight: 600;
  color: #1677ff;
  margin: 0 0 8px;
}

.login-header p {
  color: #666;
  font-size: 14px;
  margin: 0;
}

.captcha-row {
  display: flex;
  gap: 12px;
}

.captcha-row .ant-input {
  flex: 1;
}

.captcha-img {
  width: 120px;
  height: 40px;
  cursor: pointer;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  flex-shrink: 0;
}
</style>
