<template>
  <div>
    <a-card title="管理员资料" size="small" style="margin-bottom: 16px">
      <a-space direction="vertical" :size="16" style="width: 100%">
        <div style="display: flex; align-items: center; gap: 16px">
          <a-upload :show-upload-list="false" :before-upload="beforeAvatarUpload" :custom-request="uploadAvatar">
            <a-avatar :size="64" :src="avatarUrl" style="cursor: pointer; background-color: #1677ff">
              {{ username?.charAt(0)?.toUpperCase() }}
            </a-avatar>
          </a-upload>
          <div>
            <div style="font-weight: 500; font-size: 16px">{{ nickname || username }}</div>
            <div style="color: #999; font-size: 12px">点击头像上传新图片</div>
          </div>
        </div>
        <a-form layout="inline">
          <a-form-item label="昵称">
            <a-input v-model:value="nickname" placeholder="请输入昵称" style="width: 200px" />
          </a-form-item>
          <a-form-item>
            <a-button type="primary" :loading="profileSaving" @click="saveProfile">保存资料</a-button>
          </a-form-item>
        </a-form>
      </a-space>
    </a-card>

    <a-collapse v-model:activeKey="activeKeys" :bordered="false" style="background: #fff">
      <a-collapse-panel v-for="group in groups" :key="group.label" :header="group.label">
        <a-table :columns="configColumns" :data-source="group.items" :pagination="false" size="small" row-key="configKey">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'label'">
              <div style="font-weight: 500">{{ formatLabel(record) }}</div>
              <div style="font-size: 12px; color: #999; font-family: monospace">{{ record.configKey }}</div>
            </template>
            <template v-if="column.key === 'value'">
              <a-input v-if="record.configType === 'string'" v-model:value="record.configValue" size="small" style="width: 240px" />
              <a-input-number v-else-if="record.configType === 'int' || record.configType === 'long'" v-model:value="record.configValue" size="small" style="width: 200px" />
              <a-switch v-else-if="record.configType === 'boolean'" v-model:checked="record._boolVal" @change="record.configValue = record._boolVal ? 'true' : 'false'" />
            </template>
            <template v-if="column.key === 'action'">
              <a-button type="primary" size="small" :loading="record._saving" @click="saveItem(record)">保存</a-button>
            </template>
          </template>
        </a-table>
      </a-collapse-panel>
    </a-collapse>

    <a-card title="危险操作" size="small" style="margin-top: 16px; border-color: #ffccc7">
      <a-alert message="恢复默认配置将覆盖所有自定义设置，此操作不可撤销。" type="warning" show-icon style="margin-bottom: 16px" />
      <a-popconfirm title="确认恢复所有配置为默认值？" @confirm="resetAll">
        <a-button danger>恢复默认配置</a-button>
      </a-popconfirm>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const configs = ref<any[]>([])
const activeKeys = ref(['传输限制', '工作区限制'])
const avatarUrl = ref<string | undefined>(undefined)
const nickname = ref('')
const username = ref(auth.username)
const profileSaving = ref(false)

const configColumns = [
  { title: '配置项', key: 'label', width: 280 },
  { title: '当前值', key: 'value', width: 280 },
  { title: '操作', key: 'action', width: 100 }
]

const groupMap: Record<string, string[]> = {
  '传输限制': ['transfer.'], '工作区限制': ['workspace.'], '好友系统': ['friend.', 'user.allow_register'],
  'NAT / Relay': ['nat.', 'relay.'], '系统基础': ['system.']
}

const groups = computed(() => Object.entries(groupMap).map(([label, prefixes]) => ({
  label,
  items: configs.value.filter(c => prefixes.some(p => c.configKey.startsWith(p) || c.configKey === p))
})).filter(g => g.items.length > 0))

const labelMap: Record<string, string> = {
  'transfer.chunk_size': '默认分片大小', 'transfer.free_download_limit': '免费用户下行限速', 'transfer.free_upload_limit': '免费用户上行限速',
  'transfer.large_chunk_size': '大文件分片大小', 'transfer.large_file_threshold': '大文件分片阈值', 'transfer.max_file_size': '单文件最大体积',
  'transfer.paid_download_limit': '付费用户下行限速', 'transfer.paid_upload_limit': '付费用户上行限速', 'transfer.require_friend': '跨网传输需好友关系',
  'transfer.task_expire_days': '任务过期天数', 'transfer.upload_concurrency': '分片上传并发数',
  'workspace.file_retention_days': '文件保留天数', 'workspace.invite_expire_days': '邀请链接有效期', 'workspace.max_create': '每用户可创建工作区数',
  'workspace.max_file_size': '工作区单文件最大体积', 'workspace.max_join': '每用户可加入工作区数', 'workspace.max_members': '单工作区最大成员数', 'workspace.max_size': '单工作区最大空间',
  'friend.max_friends': '最大好友数', 'friend.request_expire_days': '好友请求有效期', 'user.allow_register': '允许新用户注册',
  'nat.enabled': '启用 NAT 穿透', 'nat.punch_timeout_ms': 'NAT 打洞超时', 'nat.stun_server': 'STUN 服务器地址',
  'relay.chunk_size': 'Relay 子块大小', 'relay.enabled': '启用 Relay 中继', 'relay.fallback_to_minio': 'Relay 降级到 MinIO', 'relay.timeout_seconds': 'Relay 超时',
  'system.cleanup_cron': '数据清理 Cron', 'system.jwt_access_expire_hours': 'JWT Access 有效期', 'system.jwt_refresh_expire_days': 'JWT Refresh 有效期',
  'system.name': '系统名称', 'system.public_url': '公网地址'
}

function formatLabel(item: any) { return labelMap[item.configKey] || item.description || item.configKey }

async function loadConfigs() {
  const res: any = await adminApi.getConfig()
  if (res.code === 200) configs.value = (res.data || []).map((c: any) => ({ ...c, _saving: false, _boolVal: c.configValue === 'true' }))
}

async function saveItem(item: any) {
  item._saving = true
  try { await adminApi.updateConfig(item.configKey, String(item.configValue)); message.success('已保存') }
  catch { message.error('保存失败') }
  finally { item._saving = false }
}

async function resetAll() {
  try { await adminApi.resetConfig(); loadConfigs(); message.success('已恢复默认配置') }
  catch { message.error('恢复失败') }
}

function beforeAvatarUpload(file: File) {
  const isImage = file.type.startsWith('image/')
  if (!isImage) { message.error('只能上传图片文件'); return false }
  const isLt2M = file.size / 1024 / 1024 < 2
  if (!isLt2M) { message.error('图片大小不能超过 2MB'); return false }
  return true
}

async function uploadAvatar({ file }: any) {
  try {
    const formData = new FormData()
    formData.append('file', file)
    const token = auth.token
    const res = await fetch('/api/transfer/avatar', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: formData
    })
    const data = await res.json()
    if (data.code === 200) {
      avatarUrl.value = data.data.url
      await adminApi.updateProfile(undefined, data.data.url)
      message.success('头像上传成功')
    } else {
      message.error('头像上传失败')
    }
  } catch { message.error('头像上传失败') }
}

async function saveProfile() {
  profileSaving.value = true
  try {
    await adminApi.updateProfile(nickname.value || undefined)
    message.success('资料已保存')
  } catch { message.error('保存失败') }
  finally { profileSaving.value = false }
}

async function loadProfile() {
  try {
    const res: any = await adminApi.getMe()
    if (res.code === 200) {
      avatarUrl.value = res.data?.avatarUrl
      nickname.value = res.data?.nickname || ''
    }
  } catch {}
}

onMounted(() => { loadConfigs(); loadProfile() })
</script>
