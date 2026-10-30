<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>数据总览</h2>
        <div class="sub">
          实时统计
          <span v-if="lastUpdated" class="muted"> · 更新于 {{ lastUpdated }}</span>
        </div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新数据
        </a-button>
      </div>
    </div>

    <a-alert v-if="error" type="error" show-icon :message="error" class="mb16" closable @close="error = ''">
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="stat-grid">
      <div v-for="card in cards" :key="card.key" class="stat-card">
        <div class="label">
          <span class="stat-icon"><component :is="card.icon" /></span>
          {{ card.label }}
        </div>
        <div class="value">
          <a-skeleton v-if="loading && !loaded" active :paragraph="false" :title="{ width: '58%' }" />
          <template v-else>{{ card.value }}</template>
        </div>
        <div class="hint">{{ card.hint }}</div>
      </div>
    </div>

    <a-row :gutter="16" class="mt16">
      <a-col :xs="24" :xl="16">
        <div class="content-card chart-card">
          <div class="section-title">
            <h3>近 7 天传输趋势</h3>
            <span class="muted small">按任务创建时间统计（服务器本地时区）</span>
          </div>
          <a-skeleton v-if="loading && !loaded" active :paragraph="{ rows: 6 }" />
          <TrendChart v-else :data="trend" :height="288" />
        </div>
      </a-col>

      <a-col :xs="24" :xl="8">
        <div class="content-card side-card">
          <div class="section-title">
            <h3>存储与任务</h3>
          </div>
          <div class="ring-wrap">
            <a-progress
              type="circle"
              :percent="completedRate"
              :size="132"
              :stroke-width="10"
              stroke-color="#e57153"
              :format="(p: number) => `${p}%`"
            />
            <div class="ring-caption">
              <strong>{{ data.completedTransfers }}</strong>
              <span class="muted">/ {{ data.totalTransfers }} 个任务已完成</span>
            </div>
          </div>

          <a-divider class="thin" />

          <div class="kv-list">
            <div class="kv">
              <a-tooltip title="仅统计工作区文件（workspace_file），不含个人空间——后端口径，个人文件占用未计入">
                <span class="k">工作区文件占用</span>
              </a-tooltip>
              <span class="v">{{ formatBytes(data.totalStorageUsed) }}</span>
            </div>
            <div class="kv">
              <span class="k">工作区总数</span>
              <span class="v">{{ formatNumber(data.totalWorkspaces) }}</span>
            </div>
            <div class="kv">
              <span class="k">在线设备</span>
              <span class="v">{{ formatNumber(data.activeUsers) }}</span>
            </div>
            <div class="kv">
              <span class="k">管理员 / 禁用用户</span>
              <span class="v">{{ formatNumber(userStats.admin) }} / {{ formatNumber(userStats.disabled) }}</span>
            </div>
          </div>
        </div>
      </a-col>
    </a-row>

    <a-row :gutter="16" class="mt16">
      <a-col :xs="24" :xl="16">
        <div class="content-card">
          <div class="section-title">
            <h3>快捷入口</h3>
          </div>
          <div class="quick-grid">
            <button v-for="q in quickLinks" :key="q.path" class="quick-item" @click="router.push(q.path)">
              <span class="quick-icon"><component :is="q.icon" /></span>
              <span class="quick-text">
                <strong>{{ q.title }}</strong>
                <span>{{ q.desc }}</span>
              </span>
              <ArrowRightOutlined class="quick-arrow" />
            </button>
          </div>
        </div>
      </a-col>

      <a-col :xs="24" :xl="8">
        <div class="content-card">
          <div class="section-title">
            <h3>运营提示</h3>
          </div>
          <ul class="tips">
            <li>用户列表默认按注册时间倒序，支持用户名 / 昵称搜索；邮箱列与重置密码、修改邮箱均走真实接口。</li>
            <li>公告与审计日志接口返回真实记录，空态表示后端暂无数据。</li>
            <li>发信服务在「邮件服务」页配置（SMTP / Resend 等），可在该页发送测试邮件。</li>
            <li>所有写操作都会记录在审计日志；失败时页面原样显示后端错误。</li>
          </ul>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ApartmentOutlined, ArrowRightOutlined, CloudServerOutlined, NotificationOutlined,
  ReloadOutlined, SettingOutlined, SwapOutlined, TeamOutlined, UserSwitchOutlined
} from '@ant-design/icons-vue'
import { adminApi } from '../api/admin'
import { useThemeStore } from '../stores/theme'
import { errorText } from '../utils/errors'
import { formatBytes, formatNumber } from '../utils/format'
import type { DashboardData, TransferStats, UserStats } from '../types'
import TrendChart from '../components/TrendChart.vue'

const router = useRouter()
const theme = useThemeStore()

const loading = ref(true)
const loaded = ref(false)
const error = ref('')
const lastUpdated = ref('')

const data = ref<DashboardData>({
  totalUsers: 0,
  activeUsers: 0,
  totalTransfers: 0,
  completedTransfers: 0,
  totalWorkspaces: 0,
  totalStorageUsed: 0,
  transferTrend: {}
})
const userStats = ref<UserStats>({ total: 0, active: 0, disabled: 0, admin: 0 })
const transferStats = ref<TransferStats>({ total: 0, pending: 0, uploading: 0, completed: 0, failed: 0 })

const trend = computed(() => {
  const source = data.value.transferTrend || {}
  const keys = Object.keys(source).sort()
  const ordered: Record<string, number> = {}
  for (const k of keys) ordered[k] = source[k]
  return ordered
})

const completedRate = computed(() => {
  const total = data.value.totalTransfers || 0
  if (!total) return 0
  return Math.round(((data.value.completedTransfers || 0) / total) * 100)
})

const cards = computed(() => [
  {
    key: 'users',
    label: '总用户数',
    icon: TeamOutlined,
    value: formatNumber(data.value.totalUsers),
    hint: `管理员 ${formatNumber(userStats.value.admin)} · 禁用 ${formatNumber(userStats.value.disabled)}`
  },
  {
    key: 'devices',
    label: '在线设备',
    icon: CloudServerOutlined,
    value: formatNumber(data.value.activeUsers),
    hint: '客户端心跳在线（isOnline）'
  },
  {
    key: 'transfers',
    label: '传输任务',
    icon: SwapOutlined,
    value: formatNumber(data.value.totalTransfers),
    hint: `已完成 ${formatNumber(data.value.completedTransfers)} · 进行中 ${formatNumber(
      transferStats.value.uploading + transferStats.value.pending
    )}`
  },
  {
    key: 'workspaces',
    label: '工作区',
    icon: ApartmentOutlined,
    value: formatNumber(data.value.totalWorkspaces),
    hint: `工作区文件占用 ${formatBytes(data.value.totalStorageUsed)}（不含个人空间）`
  }
])

const quickLinks = [
  { path: '/users', title: '用户管理', desc: '搜索用户、调整状态与档位', icon: UserSwitchOutlined },
  { path: '/transfers', title: '传输任务', desc: '查看进度、清理过期任务', icon: SwapOutlined },
  { path: '/announcements', title: '公告管理', desc: '发布版本更新与通知', icon: NotificationOutlined },
  { path: '/settings', title: '系统设置', desc: '在线调整系统配置项', icon: SettingOutlined }
]

function nowLabel() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [dashboardRes, statsRes, transferStatsRes] = await Promise.all([
      adminApi.dashboard(),
      adminApi.userStats(),
      adminApi.transferStats()
    ])
    if (dashboardRes.code === 200 && dashboardRes.data) data.value = dashboardRes.data
    if (statsRes.code === 200 && statsRes.data) userStats.value = statsRes.data
    if (transferStatsRes.code === 200 && transferStatsRes.data) transferStats.value = transferStatsRes.data
    loaded.value = true
    lastUpdated.value = nowLabel()
  } catch (err) {
    error.value = errorText(err, '总览数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  theme.init()
  load()
})
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt16 {
  margin-top: 16px;
}
.small {
  font-size: 12px;
}
.chart-card {
  height: 100%;
}
.side-card {
  height: 100%;
}
.ring-wrap {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 6px 0 2px;
}
.ring-caption {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ring-caption strong {
  font-size: 26px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.thin {
  margin: 18px 0 14px;
}
.kv-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.kv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.kv .k {
  color: var(--app-text-secondary);
  font-size: 13px;
}
.kv .v {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
@media (max-width: 760px) {
  .quick-grid {
    grid-template-columns: 1fr;
  }
}
.quick-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  background: transparent;
  color: inherit;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, transform 0.2s ease;
}
.quick-item:hover {
  border-color: rgba(229, 113, 83, 0.45);
  background: rgba(229, 113, 83, 0.04);
  transform: translateY(-1px);
}
.quick-icon {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-bg);
  color: var(--brand);
  font-size: 17px;
}
.quick-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.quick-text strong {
  font-size: 13.5px;
  font-weight: 600;
}
.quick-text span {
  font-size: 12px;
  color: var(--app-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quick-arrow {
  color: var(--app-text-secondary);
  font-size: 12px;
}
.tips {
  margin: 0;
  padding-left: 18px;
  color: var(--app-text-secondary);
  font-size: 12.5px;
  line-height: 2;
}
</style>
