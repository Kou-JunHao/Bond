<template>
  <div class="page-container security-page">
    <div class="page-head">
      <div>
        <h2>安全中心</h2>
        <p class="sub">管理后台登录审计、失败封禁与登录防护策略</p>
      </div>
      <div class="head-actions">
        <a-button :loading="anyLoading" @click="refreshAll">刷新</a-button>
      </div>
    </div>

    <a-alert
      v-if="anyMissing"
      class="mb16"
      type="info"
      show-icon
      message="后端接口未上线"
      description="登录日志 / 登录统计 / 封禁列表 / 登录防护策略当前返回 404（后端 task-144 待部署）。本页只展示真实状态，不填充任何占位数据；接口上线后刷新即可。"
    />

    <!-- 统计 -->
    <a-card class="content-card mb16" :bordered="false">
      <template #title>
        <div class="card-title">
          <span>登录统计</span>
          <a-radio-group v-model:value="statsDays" :disabled="statsLoading" button-style="solid" size="small">
            <a-radio-button :value="7">近 7 天</a-radio-button>
            <a-radio-button :value="30">近 30 天</a-radio-button>
            <a-radio-button :value="90">近 90 天</a-radio-button>
          </a-radio-group>
        </div>
      </template>
      <template #extra>
        <a-button type="link" size="small" :loading="statsLoading" @click="loadStats()">刷新统计</a-button>
      </template>

      <a-alert
        v-if="statsMissing"
        type="info"
        show-icon
        message="统计接口未上线（GET /api/admin/login-logs/stats）"
        description="接口返回 404，本区块暂无数据。"
      />
      <a-alert
        v-else-if="statsError"
        type="error"
        show-icon
        message="统计加载失败"
        :description="statsError"
      >
        <template #action>
          <a-button size="small" @click="loadStats()">重试</a-button>
        </template>
      </a-alert>
      <a-spin v-else :spinning="statsLoading">
        <div class="stat-grid">
          <div class="stat-card">
            <div class="label"><span class="stat-dot total" />近 {{ stats?.days ?? statsDays }} 天登录总数</div>
            <div class="value">{{ stats ? formatNumber(stats.total) : '—' }}</div>
          </div>
          <div class="stat-card">
            <div class="label"><span class="stat-dot ok" />成功</div>
            <div class="value ok-text">{{ stats ? formatNumber(stats.success) : '—' }}</div>
          </div>
          <div class="stat-card">
            <div class="label"><span class="stat-dot fail" />失败</div>
            <div class="value fail-text">{{ stats ? formatNumber(stats.failed) : '—' }}</div>
          </div>
          <div class="stat-card">
            <div class="label"><span class="stat-dot rate" />失败率</div>
            <div class="value">{{ stats ? failureRateText(stats.total, stats.success) : '—' }}</div>
            <div class="hint">失败 / 总次数</div>
          </div>
        </div>

        <a-row :gutter="16" class="stats-lower">
          <a-col :xs="24" :lg="8">
            <div class="mini-table-title">Top IP</div>
            <a-table
              size="small"
              row-key="ip"
              :columns="topIpColumns"
              :data-source="stats?.topIps ?? []"
              :pagination="false"
              :locale="{ emptyText: '暂无数据' }"
            />
          </a-col>
          <a-col :xs="24" :lg="8">
            <div class="mini-table-title">Top 账号</div>
            <a-table
              size="small"
              row-key="username"
              :columns="topUserColumns"
              :data-source="stats?.topUsers ?? []"
              :pagination="false"
              :locale="{ emptyText: '暂无数据' }"
            />
          </a-col>
          <a-col :xs="24" :lg="8">
            <div class="mini-table-title">
              每日趋势
              <span class="legend"><i class="dot ok" />成功<i class="dot fail" />失败</span>
            </div>
            <div ref="chartBox" class="chart-wrap">
              <svg
                v-if="hasChartData"
                class="daily-chart"
                :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
                preserveAspectRatio="none"
                role="img"
                :aria-label="`每日登录成功与失败次数折线图，近 ${statsDays} 天`"
              >
                <g>
                  <line
                    v-for="tick in chartGeo.yTicks"
                    :key="`grid-${tick.value}`"
                    :x1="chartPad"
                    :x2="chartWidth - chartPad"
                    :y1="tick.y"
                    :y2="tick.y"
                    class="grid-line"
                  />
                  <text
                    v-for="tick in chartGeo.yTicks"
                    :key="`tick-${tick.value}`"
                    :x="chartPad - 6"
                    :y="tick.y + 3.5"
                    class="axis-label"
                    text-anchor="end"
                  >
                    {{ tick.value }}
                  </text>
                </g>
                <polyline
                  :points="successPolyline"
                  fill="none"
                  stroke="var(--app-success)"
                  stroke-width="2"
                  stroke-linejoin="round"
                  stroke-linecap="round"
                />
                <polyline
                  :points="failedPolyline"
                  fill="none"
                  stroke="var(--app-danger)"
                  stroke-width="2"
                  stroke-linejoin="round"
                  stroke-linecap="round"
                />
                <circle
                  v-for="(p, i) in chartGeo.successPoints"
                  :key="`ok-${i}`"
                  :cx="p.x"
                  :cy="p.y"
                  r="2.2"
                  fill="var(--app-success)"
                >
                  <title>成功 {{ chartSeries[i]?.success ?? 0 }}</title>
                </circle>
                <circle
                  v-for="(p, i) in chartGeo.failedPoints"
                  :key="`fail-${i}`"
                  :cx="p.x"
                  :cy="p.y"
                  r="2.2"
                  fill="var(--app-danger)"
                >
                  <title>失败 {{ chartSeries[i]?.failed ?? 0 }}</title>
                </circle>
                <text
                  v-for="label in chartGeo.labels"
                  :key="label.text + label.x"
                  :x="label.x"
                  :y="label.y"
                  class="axis-label"
                  text-anchor="middle"
                >
                  {{ label.text.slice(5) }}
                </text>
              </svg>
              <div v-else class="chart-empty">暂无数据</div>
            </div>
          </a-col>
        </a-row>
      </a-spin>
    </a-card>

    <!-- 登录日志 -->
    <a-card class="content-card table-card mb16" :bordered="false">
      <template #title>
        <span>登录日志</span>
      </template>
      <template #extra>
        <span class="muted">仅统计管理后台登录尝试（X-Client-App: bond-admin 或 is_admin 账号）</span>
      </template>

      <a-alert
        v-if="logsMissing"
        class="mb16"
        type="info"
        show-icon
        message="登录日志接口未上线（GET /api/admin/login-logs）"
        description="接口返回 404，暂无真实日志可展示。"
      />
      <a-alert
        v-else-if="logsError"
        class="mb16"
        type="error"
        show-icon
        message="登录日志加载失败"
        :description="logsError"
      >
        <template #action>
          <a-button size="small" @click="loadLogs()">重试</a-button>
        </template>
      </a-alert>

      <div class="filter-bar">
        <a-input
          v-model:value="filters.ip"
          class="filter-ip"
          placeholder="IP"
          allow-clear
          aria-label="按 IP 筛选"
          @press-enter="applyFilters"
        />
        <a-input
          v-model:value="filters.username"
          class="filter-user"
          placeholder="账号"
          allow-clear
          aria-label="按账号筛选"
          @press-enter="applyFilters"
        />
        <a-select
          v-model:value="filters.outcome"
          class="filter-outcome"
          placeholder="结果"
          allow-clear
          :options="outcomeOptions"
          aria-label="按登录结果筛选"
        />
        <a-select
          v-model:value="filters.range"
          class="filter-range"
          :options="rangeOptions"
          aria-label="按时间范围筛选"
        />
        <a-range-picker
          v-if="filters.range === 'custom'"
          v-model:value="customRange"
          class="filter-custom"
          value-format="YYYY-MM-DD"
          :allow-clear="true"
          @change="onCustomRangeChange"
        />
        <a-button type="primary" :loading="logsLoading" @click="applyFilters">查询</a-button>
        <a-button :disabled="logsLoading" @click="resetFilters">重置</a-button>
      </div>

      <a-table
        :columns="logColumns"
        :data-source="logs"
        :loading="logsLoading"
        :pagination="false"
        row-key="id"
        size="middle"
        :locale="{ emptyText: logEmptyText }"
        :row-class-name="(record: LoginLogItem) => (record.blockedNow ? 'row-blocked' : '')"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            <span>{{ formatDateTime(record.createdAtMillis) }}</span>
          </template>
          <template v-else-if="column.key === 'username'">
            <span class="cell-main">{{ record.username || '—' }}</span>
            <span v-if="record.userId" class="cell-sub">ID {{ record.userId }}</span>
          </template>
          <template v-else-if="column.key === 'ip'">
            <span class="mono">{{ record.ip || '—' }}</span>
            <a-tag v-if="record.blockedNow" color="error" class="blocked-tag">当前已封禁</a-tag>
          </template>
          <template v-else-if="column.key === 'outcome'">
            <a-tag :color="outcomeTagColor(record.outcome)">{{ outcomeLabel(record.outcome) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'userAgent'">
            <a-tooltip :title="record.userAgent || '（未记录）'">
              <span class="ua-cell">{{ truncateText(record.userAgent, 42) }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'reason'">
            <a-tooltip :title="record.reason || '（无）'">
              <span class="muted">{{ truncateText(record.reason, 24) }}</span>
            </a-tooltip>
          </template>
        </template>
      </a-table>
      <div class="table-foot">
        <span class="muted">第 {{ page }} 页 · 本页 {{ logs.length }} 条 · 共 {{ formatNumber(total) }} 条</span>
        <a-pagination
          v-model:current="page"
          v-model:page-size="pageSize"
          :total="total"
          :show-size-changer="true"
          :page-size-options="['20', '50', '100']"
          :disabled="logsLoading || logsMissing"
          @change="onPageChange"
        />
      </div>
    </a-card>

    <!-- 封禁管理 -->
    <a-card class="content-card table-card mb16" :bordered="false">
      <template #title>
        <span>封禁管理 <span class="muted">（当前生效）</span></span>
      </template>
      <template #extra>
        <a-button size="small" :loading="blocksLoading" @click="loadBlocks()">刷新</a-button>
      </template>

      <a-alert
        v-if="blocksMissing"
        class="mb16"
        type="info"
        show-icon
        message="封禁列表接口未上线（GET /api/admin/login-blocks）"
        description="接口返回 404，暂无真实封禁记录。"
      />
      <a-alert
        v-else-if="blocksError"
        class="mb16"
        type="error"
        show-icon
        message="封禁列表加载失败"
        :description="blocksError"
      >
        <template #action>
          <a-button size="small" @click="loadBlocks()">重试</a-button>
        </template>
      </a-alert>

      <a-table
        :columns="blockColumns"
        :data-source="blocks"
        :loading="blocksLoading"
        :pagination="false"
        row-key="id"
        size="middle"
        :locale="{ emptyText: blocksMissing ? '接口未上线，暂无数据' : '当前没有封禁记录' }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'scope'">
            <a-tag :color="record.scope === 'ip' ? 'blue' : 'purple'">{{ record.scope === 'ip' ? 'IP' : '账号' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'value'">
            <span class="mono">{{ record.value }}</span>
          </template>
          <template v-else-if="column.key === 'remaining'">
            <span :class="remainingSeconds(record) > 0 ? 'warn-text' : 'muted'">
              {{ remainingSeconds(record) > 0 ? countdownLabel(remainingSeconds(record)) : '已到期' }}
            </span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" danger :disabled="!record.active" @click="confirmLift(record)">解除封禁</a-button>
          </template>
          <template v-else-if="column.key === 'bannedAt'">
            <span>{{ formatDateTime(record.bannedAtMillis) }}</span>
          </template>
          <template v-else-if="column.key === 'reason'">
            <a-tooltip :title="record.reason || '（无）'">
              <span class="muted">{{ truncateText(record.reason, 28) }}</span>
            </a-tooltip>
          </template>
        </template>
      </a-table>
      <div class="table-foot">
        <span class="muted">第 {{ blockPage }} 页 · 共 {{ formatNumber(blocksTotal) }} 条</span>
        <a-pagination
          v-model:current="blockPage"
          v-model:page-size="blockPageSize"
          :total="blocksTotal"
          :show-size-changer="false"
          :disabled="blocksLoading || blocksMissing"
          @change="loadBlocks"
        />
      </div>
    </a-card>

    <!-- 防护策略 -->
    <a-card class="content-card mb16" :bordered="false">
      <template #title>
        <span>登录防护策略</span>
      </template>
      <template #extra>
        <span class="muted">PUT /api/admin/login-guard（部分更新，仅提交改动字段）</span>
      </template>

      <a-alert
        v-if="guardMissing"
        type="info"
        show-icon
        message="策略接口未上线（GET /api/admin/login-guard）"
        description="接口返回 404，下列字段仅为界面骨架（未加载真实值、全部禁用），不会提交任何数据。"
      />
      <a-alert
        v-else-if="guardError"
        type="error"
        show-icon
        message="策略加载失败"
        :description="guardError"
      >
        <template #action>
          <a-button size="small" @click="loadGuard()">重试</a-button>
        </template>
      </a-alert>
      <a-alert
        v-if="!guardMissing && guardSaveError"
        class="mb12"
        type="error"
        show-icon
        closable
        message="保存失败（后端原文）"
        :description="guardSaveError"
        @close="guardSaveError = ''"
      />

      <a-spin :spinning="guardLoading">
        <a-form layout="vertical" class="guard-form">
          <a-row :gutter="16">
            <a-col :xs="24" :md="8">
              <a-form-item label="启用登录防护">
                <a-switch
                  v-model:checked="guardForm.enabled"
                  :disabled="guardMissing"
                  checked-children="启用"
                  un-checked-children="关闭"
                />
                <div class="field-hint">关闭后不再累计失败次数，也不会自动封禁</div>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-form-item label="失败计数窗口（秒）">
                <a-input-number
                  v-model:value="guardForm.failWindowSeconds"
                  class="num-input"
                  :step="60"
                  :disabled="guardMissing"
                  placeholder="未加载"
                />
                <div class="field-hint">后端要求 60–86400</div>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-form-item label="封禁时长（秒）">
                <a-input-number
                  v-model:value="guardForm.banSeconds"
                  class="num-input"
                  :step="60"
                  :disabled="guardMissing"
                  placeholder="未加载"
                />
                <div class="field-hint">后端要求 60–86400</div>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-form-item label="同一 IP 失败阈值">
                <a-input-number
                  v-model:value="guardForm.failThresholdIp"
                  class="num-input"
                  :step="1"
                  :disabled="guardMissing"
                  placeholder="未加载"
                />
                <div class="field-hint">后端要求 1–100 次</div>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-form-item label="同一账号失败阈值">
                <a-input-number
                  v-model:value="guardForm.failThresholdUser"
                  class="num-input"
                  :step="1"
                  :disabled="guardMissing"
                  placeholder="未加载"
                />
                <div class="field-hint">后端要求 1–100 次</div>
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="IP 白名单">
            <a-textarea
              v-model:value="allowlistText"
              :rows="3"
              :disabled="guardMissing"
              placeholder="每行一个 IP，也可用英文逗号分隔；仅接受单个 IPv4/IPv6 字面量"
            />
            <div class="field-hint">
              白名单内的 IP 永不被封禁；后端只接受 IP 字面量（不支持 10.0.0.0/8 这类网段写法）。
            </div>
          </a-form-item>
          <div class="guard-actions">
            <a-button type="primary" :loading="guardSaving" :disabled="guardMissing" @click="saveGuard">保存策略</a-button>
            <a-button :disabled="guardSaving || guardMissing" @click="loadGuard()">还原</a-button>
            <span v-if="guardSavedHint" class="saved-hint">{{ guardSavedHint }}</span>
          </div>
        </a-form>
      </a-spin>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { securityApi } from '../api/security'
import type {
  LoginBlockItem,
  LoginGuardConfig,
  LoginGuardPatch,
  LoginLogItem,
  LoginLogStats,
  LoginOutcome
} from '../types'
import { formatDateTime, formatNumber } from '../utils/format'
import { errorText, isEndpointMissing } from '../utils/errors'
import {
  LOGIN_OUTCOME_OPTIONS,
  TIME_RANGE_OPTIONS,
  buildDailySeries,
  countdownLabel,
  dailyChartGeometry,
  failureRateText,
  filtersToQuery,
  liftConfirmText,
  outcomeLabel,
  outcomeTagColor,
  pointsToPolyline,
  queryToFilters,
  rangeToFromTo,
  truncateText,
  type TimeRangeKey
} from '../utils/security'

const route = useRoute()
const router = useRouter()

const outcomeOptions = LOGIN_OUTCOME_OPTIONS.map((o) => ({ value: o.value, label: o.label }))
const rangeOptions = TIME_RANGE_OPTIONS.map((o) => ({ value: o.value, label: o.label }))

// ---------------------------------------------------------------- filters
const filters = reactive({
  ip: '',
  username: '',
  outcome: undefined as LoginOutcome | undefined,
  range: '7d' as TimeRangeKey,
  from: undefined as string | undefined,
  to: undefined as string | undefined
})
const customRange = ref<unknown>(null)

const page = ref(1)
const pageSize = ref(20)

// ---------------------------------------------------------------- logs
const logs = ref<LoginLogItem[]>([])
const total = ref(0)
const logsLoading = ref(false)
const logsError = ref('')
const logsMissing = ref(false)

const logColumns = [
  { title: '时间', key: 'createdAt', width: 150 },
  { title: '账号', key: 'username', width: 170 },
  { title: 'IP', key: 'ip', width: 190 },
  { title: '结果', key: 'outcome', width: 110 },
  { title: '说明', key: 'reason', width: 170 },
  { title: '用户代理', key: 'userAgent', width: 260 }
]

const logEmptyText = computed(() => {
  if (logsMissing.value) {
    return '登录日志接口未上线，暂无数据'
  }
  if (filters.ip || filters.username || filters.outcome) {
    return '没有符合条件的登录记录'
  }
  return '暂无登录记录'
})

// ---------------------------------------------------------------- stats
const statsDays = ref(7)
const stats = ref<LoginLogStats | null>(null)
const statsLoading = ref(false)
const statsError = ref('')
const statsMissing = ref(false)

const topIpColumns = [
  { title: 'IP', dataIndex: 'ip', key: 'ip' },
  { title: '次数', dataIndex: 'count', key: 'count', width: 70, align: 'right' as const },
  { title: '失败', dataIndex: 'failed', key: 'failed', width: 70, align: 'right' as const }
]
const topUserColumns = [
  { title: '账号', dataIndex: 'username', key: 'username' },
  { title: '次数', dataIndex: 'count', key: 'count', width: 70, align: 'right' as const },
  { title: '失败', dataIndex: 'failed', key: 'failed', width: 70, align: 'right' as const }
]

/**
 * 折线图：接口 daily[] 只返回「有登录记录」的日期，先补齐成连续序列再画，
 * 否则 30/90 天档位的点会挤在两端、X 轴日期也不对。
 * 宽度用 ResizeObserver 实测（viewBox 与容器同尺寸 → 文字不被拉伸变形）。
 */
const chartBox = ref<HTMLElement | null>(null)
const chartWidth = ref(560)
const chartHeight = 190
const chartPad = 30
const chartRawRows = computed(() => (Array.isArray(stats.value?.daily) ? stats.value!.daily! : []))
const chartSeries = computed(() => buildDailySeries(chartRawRows.value, statsDays.value))
const hasChartData = computed(() => chartRawRows.value.length > 0)
const chartGeo = computed(() => dailyChartGeometry(chartSeries.value, chartWidth.value, chartHeight, chartPad))
const successPolyline = computed(() => pointsToPolyline(chartGeo.value.successPoints))
const failedPolyline = computed(() => pointsToPolyline(chartGeo.value.failedPoints))

let chartResizeObserver: ResizeObserver | undefined
function syncChartWidth() {
  const width = chartBox.value?.clientWidth
  if (width && width > 0) chartWidth.value = Math.max(320, Math.round(width))
}

// ---------------------------------------------------------------- blocks
const blocks = ref<LoginBlockItem[]>([])
const blockPage = ref(1)
const blockPageSize = ref(10)
const blocksTotal = ref(0)
const blocksLoading = ref(false)
const blocksError = ref('')
const blocksMissing = ref(false)

const blockColumns = [
  { title: '类型', key: 'scope', width: 80 },
  { title: '对象', key: 'value', width: 220 },
  { title: '失败次数', dataIndex: 'failCount', key: 'failCount', width: 100, align: 'right' as const },
  { title: '原因', key: 'reason', width: 260 },
  { title: '封禁时间', key: 'bannedAt', width: 160 },
  { title: '剩余时间', key: 'remaining', width: 150 },
  { title: '操作', key: 'action', width: 110, fixed: 'right' as const }
]

const nowTick = ref(Date.now())
let tickTimer: number | undefined

/** 剩余秒数：优先用 expiresAtMillis 实时计算，后端 remainingSeconds 作为兜底 */
function remainingSeconds(block: LoginBlockItem): number {
  if (block.expiresAtMillis) {
    const left = Math.floor((block.expiresAtMillis - nowTick.value) / 1000)
    return Math.max(0, left)
  }
  return Math.max(0, block.remainingSeconds || 0)
}

// ---------------------------------------------------------------- guard
const guardForm = reactive<{
  enabled: boolean
  failWindowSeconds: number | null
  failThresholdIp: number | null
  failThresholdUser: number | null
  banSeconds: number | null
}>({
  enabled: false,
  failWindowSeconds: null,
  failThresholdIp: null,
  failThresholdUser: null,
  banSeconds: null
})
const allowlistText = ref('')
const guardLoading = ref(false)
const guardSaving = ref(false)
const guardError = ref('')
const guardMissing = ref(false)
const guardSaveError = ref('')
const guardSavedHint = ref('')
let savedHintTimer: number | undefined

function buildGuardPatch(): LoginGuardPatch {
  const allowlist = allowlistText.value
    .split(/[\n,]+/)
    .map((part) => part.trim())
    .filter(Boolean)
  const patch: LoginGuardPatch = { enabled: guardForm.enabled, ipAllowlist: allowlist }
  if (guardForm.failWindowSeconds != null) patch.failWindowSeconds = guardForm.failWindowSeconds
  if (guardForm.failThresholdIp != null) patch.failThresholdIp = guardForm.failThresholdIp
  if (guardForm.failThresholdUser != null) patch.failThresholdUser = guardForm.failThresholdUser
  if (guardForm.banSeconds != null) patch.banSeconds = guardForm.banSeconds
  return patch
}

// ---------------------------------------------------------------- state helpers
const anyMissing = computed(
  () => logsMissing.value || statsMissing.value || blocksMissing.value || guardMissing.value
)
const anyLoading = computed(
  () => logsLoading.value || statsLoading.value || blocksLoading.value || guardLoading.value
)

function markSavedHint(text: string) {
  guardSavedHint.value = text
  if (savedHintTimer) window.clearTimeout(savedHintTimer)
  savedHintTimer = window.setTimeout(() => {
    guardSavedHint.value = ''
  }, 4000)
}

// ---------------------------------------------------------------- loaders
async function loadLogs() {
  logsLoading.value = true
  logsError.value = ''
  try {
    const range = rangeToFromTo(filters.range, filters.from, filters.to)
    const res = await securityApi.listLoginLogs({
      page: page.value,
      size: pageSize.value,
      ip: filters.ip.trim() || undefined,
      username: filters.username.trim() || undefined,
      outcome: filters.outcome,
      from: range.from,
      to: range.to
    })
    logsMissing.value = false
    if (res.code === 200 && res.data) {
      logs.value = res.data.items ?? []
      total.value = res.data.total ?? 0
    } else {
      logsError.value = res.message || '加载失败'
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      logsMissing.value = true
      logs.value = []
      total.value = 0
    } else {
      logsError.value = errorText(err, '登录日志加载失败')
    }
  } finally {
    logsLoading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  try {
    const res = await securityApi.loginLogStats(statsDays.value)
    statsMissing.value = false
    if (res.code === 200 && res.data) {
      stats.value = res.data
    } else {
      statsError.value = res.message || '加载失败'
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      statsMissing.value = true
      stats.value = null
    } else {
      statsError.value = errorText(err, '登录统计加载失败')
    }
  } finally {
    statsLoading.value = false
  }
}

async function loadBlocks() {
  blocksLoading.value = true
  blocksError.value = ''
  try {
    const res = await securityApi.listLoginBlocks({
      active: true,
      page: blockPage.value,
      size: blockPageSize.value
    })
    blocksMissing.value = false
    if (res.code === 200 && res.data) {
      blocks.value = res.data.items ?? []
      blocksTotal.value = res.data.total ?? 0
    } else {
      blocksError.value = res.message || '加载失败'
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      blocksMissing.value = true
      blocks.value = []
      blocksTotal.value = 0
    } else {
      blocksError.value = errorText(err, '封禁列表加载失败')
    }
  } finally {
    blocksLoading.value = false
  }
}

async function loadGuard() {
  guardLoading.value = true
  guardError.value = ''
  try {
    const res = await securityApi.getLoginGuard()
    guardMissing.value = false
    const config: LoginGuardConfig | null = res.code === 200 ? res.data : null
    if (config) {
      guardForm.enabled = config.enabled
      guardForm.failWindowSeconds = config.failWindowSeconds
      guardForm.failThresholdIp = config.failThresholdIp
      guardForm.failThresholdUser = config.failThresholdUser
      guardForm.banSeconds = config.banSeconds
      allowlistText.value = (config.ipAllowlist ?? []).join('\n')
    } else {
      throw new Error(res.message || '加载失败')
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      guardMissing.value = true
    } else {
      guardError.value = errorText(err, '防护策略加载失败')
    }
  } finally {
    guardLoading.value = false
  }
}

function refreshAll() {
  void loadLogs()
  void loadStats()
  void loadBlocks()
  void loadGuard()
}

// ---------------------------------------------------------------- filters actions
function syncQuery() {
  const query: Record<string, string> = { ...filtersToQuery(filters) }
  if (page.value > 1) query.page = String(page.value)
  if (statsDays.value !== 7) query.days = String(statsDays.value)
  void router.replace({ query })
}

function applyFilters() {
  page.value = 1
  syncQuery()
  void loadLogs()
}

function resetFilters() {
  filters.ip = ''
  filters.username = ''
  filters.outcome = undefined
  filters.range = '7d'
  filters.from = undefined
  filters.to = undefined
  customRange.value = null
  page.value = 1
  syncQuery()
  void loadLogs()
}

function onCustomRangeChange(value: unknown) {
  if (Array.isArray(value) && value.length === 2) {
    filters.from = String(value[0])
    filters.to = String(value[1])
  } else {
    filters.from = undefined
    filters.to = undefined
  }
  applyFilters()
}

/** 204 空响应没有 Result 信封，统一按成功处理 */
function resultCode(res: unknown): number {
  const body = res as { code?: number } | null | undefined
  return body?.code ?? 204
}

function onPageChange() {
  syncQuery()
  void loadLogs()
}

// ---------------------------------------------------------------- actions
function confirmLift(block: LoginBlockItem) {
  Modal.confirm({
    title: '解除封禁',
    content: liftConfirmText({
      scope: block.scope,
      value: block.value,
      remainingSeconds: remainingSeconds(block)
    }),
    okText: '立即解除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      try {
        const res = await securityApi.liftLoginBlock(block.id)
        const code = resultCode(res)
        if (code === 200 || code === 204) {
          message.success('已解除封禁')
          await Promise.all([loadBlocks(), loadLogs()])
        } else {
          const body = res as { message?: string } | null
          message.error(body?.message || `解除失败（code=${code}）`)
        }
      } catch (err) {
        message.error(errorText(err, '解除失败'))
      }
    }
  })
}

async function saveGuard() {
  guardSaving.value = true
  guardSaveError.value = ''
  try {
    const res = await securityApi.updateLoginGuard(buildGuardPatch())
    if (res.code === 200) {
      const config = res.data
      if (config) {
        guardForm.enabled = config.enabled
        guardForm.failWindowSeconds = config.failWindowSeconds
        guardForm.failThresholdIp = config.failThresholdIp
        guardForm.failThresholdUser = config.failThresholdUser
        guardForm.banSeconds = config.banSeconds
        allowlistText.value = (config.ipAllowlist ?? []).join('\n')
      }
      markSavedHint('已保存（后端已接受）')
      message.success('策略已保存')
    } else {
      guardSaveError.value = res.message || '保存失败'
    }
  } catch (err) {
    // 边界校验以后端为准：把后端 message 原文展示出来（不自己编文案）
    guardSaveError.value = errorText(err, '保存失败')
    message.error('保存失败，详见页面提示')
  } finally {
    guardSaving.value = false
  }
}

// ---------------------------------------------------------------- init
onMounted(() => {
  const parsed = queryToFilters(route.query as Record<string, unknown>)
  filters.ip = parsed.ip
  filters.username = parsed.username
  filters.outcome = parsed.outcome
  filters.range = parsed.range
  filters.from = parsed.from
  filters.to = parsed.to
  if (parsed.range === 'custom' && parsed.from && parsed.to) {
    customRange.value = [parsed.from, parsed.to]
  }
  const urlPage = Number(route.query.page)
  if (Number.isFinite(urlPage) && urlPage > 1) {
    page.value = Math.floor(urlPage)
  }
  const urlDays = Number(route.query.days)
  if ([7, 30, 90].includes(urlDays)) {
    statsDays.value = urlDays
  }
  refreshAll()
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
  // 图表宽度跟随容器（避免 viewBox 固定 640 被拉伸导致文字重叠/图形不可见）
  nextTick(() => {
    syncChartWidth()
    if (chartBox.value && typeof ResizeObserver !== 'undefined') {
      chartResizeObserver = new ResizeObserver(() => syncChartWidth())
      chartResizeObserver.observe(chartBox.value)
    }
  })
})

onUnmounted(() => {
  if (tickTimer) window.clearInterval(tickTimer)
  if (savedHintTimer) window.clearTimeout(savedHintTimer)
  chartResizeObserver?.disconnect()
})

watch(
  () => statsDays.value,
  () => {
    syncQuery()
    void loadStats()
  }
)
</script>

<style scoped>
.security-page {
  padding-bottom: 24px;
}

.mb16 {
  margin-bottom: 16px;
}

.mb12 {
  margin-bottom: 12px;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sub {
  margin: 4px 0 0;
  color: var(--app-text-secondary);
  font-size: 13px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.stat-card {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--app-bg);
  border: 1px solid var(--app-border);
}

.stat-card .label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.stat-card .value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.stat-card .hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.stat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.stat-dot.total {
  background: var(--brand);
}

.stat-dot.ok {
  background: var(--app-success);
}

.stat-dot.fail {
  background: var(--app-danger);
}

.stat-dot.rate {
  background: var(--app-warning, #d89614);
}

.ok-text {
  color: var(--app-success);
}

.fail-text {
  color: var(--app-danger);
}

.warn-text {
  color: var(--app-warning, #d89614);
}

.stats-lower {
  margin-top: 16px;
}

.mini-table-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 400;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  margin: 0 2px 0 8px;
}

.legend .dot.ok {
  background: var(--app-success);
}

.legend .dot.fail {
  background: var(--app-danger);
}

.chart-wrap {
  height: 190px;
  border: 1px dashed var(--app-border);
  border-radius: 10px;
  padding: 4px;
}

.daily-chart {
  width: 100%;
  height: 100%;
  display: block;
  overflow: visible;
}

.grid-line {
  stroke: var(--app-border);
  stroke-width: 1;
  stroke-dasharray: 3 4;
}

.axis-label {
  fill: var(--app-text-secondary);
  font-size: 11px;
}

.chart-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-secondary);
  font-size: 13px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.filter-ip {
  width: 150px;
}

.filter-user {
  width: 160px;
}

.filter-outcome {
  width: 130px;
}

.filter-range {
  width: 120px;
}

.filter-custom {
  width: 250px;
}

.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 12px;
}

.muted {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.cell-main {
  color: var(--app-text);
}

.cell-sub {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--app-text-secondary);
}

.ua-cell {
  display: inline-block;
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.blocked-tag {
  margin-left: 6px;
}

.guard-form {
  max-width: 980px;
}

.num-input {
  width: 100%;
}

.field-hint {
  margin-top: 4px;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.guard-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.saved-hint {
  color: var(--app-success);
  font-size: 12px;
}

:deep(.row-blocked) td {
  background: rgba(220, 68, 70, 0.06) !important;
}
</style>
